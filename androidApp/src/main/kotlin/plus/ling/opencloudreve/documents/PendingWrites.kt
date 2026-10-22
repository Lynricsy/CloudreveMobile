package plus.ling.opencloudreve.documents

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.transfer.UploadSources
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 系统文件集成的一次写入（外部应用通过可写描述符保存内容）。在交出描述符之前写入日志，
 * 关闭时标记为已提交；交给传输队列（建好上传任务）后删除。进程在任一时刻被回收，启动后都能据此恢复。
 */
@Serializable
internal data class PendingWrite(
    val id: String,
    val accountId: String,
    /** 被写入的文档 ID（完成后通知该文档 URI） */
    val documentId: String,
    /** 打开时所在目录（上传目标目录） */
    val parentUri: String,
    val name: String,
    /** 打开时服务端已有内容的大小；null = 原本不存在（新建） */
    val existingSize: Long?,
    /** 内容文件（收件箱内） */
    val temp: String,
    /** 打开描述符的进程（[PendingWrites.processToken]） */
    val process: String,
    /** 写入方已正常关闭描述符 */
    val closed: Boolean = false,
)

/** 写入日志：每条一个 JSON 文件，原子替换（写临时文件 → fsync → rename） */
internal class PendingWriteJournal(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun put(record: PendingWrite) {
        dir.mkdirs()
        val tmp = File(dir, "${record.id}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(PendingWrite.serializer(), record).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, "${record.id}$SUFFIX"))) {
            tmp.delete()
            throw java.io.IOException("journal rename failed")
        }
    }

    fun remove(id: String) {
        File(dir, "$id$SUFFIX").delete()
    }

    fun all(): List<PendingWrite> = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { f ->
        runCatching { json.decodeFromString(PendingWrite.serializer(), f.readText()) }
            .onFailure { f.delete() }
            .getOrNull()
    }

    private companion object {
        const val SUFFIX = ".json"
    }
}

/** 恢复时对一条日志的处理 */
internal enum class PendingWriteAction {
    /** 本进程仍打开着描述符：等关闭 */
    WAIT,

    /** 正常关闭的写入：入队上传 */
    SUBMIT,

    /** 写入方关闭前提供方进程已结束，无法确认内容完整：建暂停的上传任务，由用户在传输列表决定继续或移除 */
    SUBMIT_PAUSED,

    /** 没有需要上传的内容：删除内容文件与日志 */
    DROP_CONTENT,

    /** 内容已由上传任务接管或已不存在：只删日志 */
    DROP_RECORD,
}

internal object PendingWrites {
    private const val TAG = "PendingWrites"
    private const val DIR = "documents-pending"
    private const val WORK_NAME = "documents-pending-writes"

    /** 本进程标识：日志里的进程标识与之不同且仍未关闭，说明打开它的进程已不在，关闭回调不会再来 */
    val processToken: String = UUID.randomUUID().toString()

    private val mutex = Mutex()

    fun journal(context: Context) = PendingWriteJournal(File(context.filesDir, DIR))

    /**
     * [tempLength] 为内容文件长度（不存在为 null）；[alreadyQueued] 表示已有上传任务以该文件为源。
     *
     * 空内容也是合法保存（把非空文件清空）：与非空内容一样进入上传队列，只有服务端原本就为空时跳过。
     * 未关闭的孤儿写入可能只写了一半：非空时建暂停任务，空文件无法区分“清空”与“尚未写入”，不提交。
     */
    fun decide(record: PendingWrite, currentProcess: String, tempLength: Long?, alreadyQueued: Boolean): PendingWriteAction = when {
        alreadyQueued || tempLength == null -> PendingWriteAction.DROP_RECORD
        !record.closed && record.process == currentProcess -> PendingWriteAction.WAIT
        !record.closed -> if (tempLength == 0L) PendingWriteAction.DROP_CONTENT else PendingWriteAction.SUBMIT_PAUSED
        tempLength == 0L && record.existingSize == 0L -> PendingWriteAction.DROP_CONTENT
        else -> PendingWriteAction.SUBMIT
    }

    /** 调度处理（WorkManager 持久保存请求；进程被回收后也会重新拉起执行） */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequest.Builder(PendingWriteWorker::class.java).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** 把日志中可提交的写入交给传输队列；失败的条目保留，返回是否全部处理成功 */
    suspend fun process(context: Context): Boolean = mutex.withLock {
        val journal = journal(context)
        var ok = true
        for (record in journal.all()) {
            try {
                handle(context, journal, record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pending write ${record.id} not handed off", e)
                ok = false
            }
        }
        ok
    }

    private suspend fun handle(context: Context, journal: PendingWriteJournal, record: PendingWrite) {
        val temp = File(record.temp)
        if (CoreGraph.accounts().account(record.accountId) == null) {
            // 账户已移除：内容无处可传
            UploadSources.discard(context, record.temp)
            journal.remove(record.id)
            return
        }
        val repository = CoreGraph.transferRepository()
        val queued = repository.observeTasks(record.accountId).first().any { it.sourceRef == record.temp }
        val length = if (temp.isFile) temp.length() else null
        when (decide(record, processToken, length, queued)) {
            PendingWriteAction.WAIT -> Unit
            PendingWriteAction.DROP_RECORD -> journal.remove(record.id)
            PendingWriteAction.DROP_CONTENT -> {
                UploadSources.discard(context, record.temp)
                journal.remove(record.id)
            }
            PendingWriteAction.SUBMIT -> {
                val item = CoreGraph.transferManager()
                    .enqueueUploads(record.accountId, listOf(source(record, temp)), record.parentUri, policy = null, overwrite = record.existingSize != null)
                    .first()
                journal.remove(record.id)
                // 未通过预校验的任务不会调度，重试也不会通过：不保留内容
                if (item.state == TransferState.FAILED) UploadSources.discard(context, record.temp)
            }
            PendingWriteAction.SUBMIT_PAUSED -> {
                repository.createUpload(
                    record.accountId, source(record, temp), record.parentUri,
                    overwrite = record.existingSize != null,
                    initialState = TransferState.PAUSED,
                )
                journal.remove(record.id)
            }
        }
    }

    private fun source(record: PendingWrite, temp: File) =
        UploadSource(record.temp, record.name, temp.length(), temp.lastModified(), DocumentRows.mimeOfName(record.name))
}

/** 处理系统文件集成的待提交写入 */
class PendingWriteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (PendingWrites.process(applicationContext)) Result.success() else Result.retry()
}
