package plus.ling.opencloudreve.core.transfer

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.db.TransferChunk
import plus.ling.opencloudreve.core.db.TransferTask
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 分片状态（DB `TransferChunk.state`） */
internal object ChunkState {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
}

/**
 * 传输任务持久化（`Transfer.sq`）。规划器与执行器共享它；UI 通过 [observeTasks] / [observeSummary] 订阅。
 */
class TransferRepository(
    private val database: OpenCloudreveDatabase,
    private val clock: () -> Instant = { Clock.System.now() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val q get() = database.transferQueries

    private fun now(): Long = clock().toEpochMilliseconds()

    /** 账户的全部任务，新建在前 */
    fun observeTasks(accountId: String): Flow<List<TransferItem>> =
        q.selectTasksByAccount(accountId).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toItem() } }

    /** 汇总活动任务进度；[accountId] 为 null 时统计全部账户 */
    fun observeSummary(accountId: String? = null): Flow<TransferSummary> {
        val query = if (accountId == null) q.selectAllTasks() else q.selectTasksByAccount(accountId)
        return query.asFlow().mapToList(dispatcher).map { rows -> summarize(rows.map { it.toItem() }) }
    }

    @Throws(Exception::class)
    suspend fun task(id: String): TransferItem? = io { q.selectTask(id).executeAsOneOrNull()?.toItem() }

    /** 需要执行器接管的任务（queued/preparing/running/finishing），按创建顺序 */
    @Throws(Exception::class)
    suspend fun unfinishedTasks(): List<TransferItem> = io { q.selectUnfinishedTasks().executeAsList().map { it.toItem() } }

    /** 新建上传任务：[targetDirUri] 为目标目录，目标文件 URI = 目录 + [UploadSource.name] */
    @Throws(Exception::class)
    suspend fun createUpload(
        accountId: String,
        source: UploadSource,
        targetDirUri: String,
        overwrite: Boolean = false,
        initialState: TransferState = TransferState.QUEUED,
        errorCode: Int? = null,
        errorMessage: String? = null,
    ): TransferItem = io {
        val id = Uuid.random().toString()
        val ts = now()
        q.insertTask(
            id = id,
            account_id = accountId,
            kind = TransferKind.UPLOAD.wire,
            state = initialState.wire,
            source_ref = source.sourceRef,
            display_name = source.name,
            size = source.size,
            last_modified = source.lastModified,
            mime_type = source.mimeType,
            target_uri = joinUri(targetDirUri, source.name),
            dest_path = null,
            overwrite = if (overwrite) 1 else 0,
            error_code = errorCode?.toLong(),
            error_msg = errorMessage,
            created_at = ts,
            updated_at = ts,
        )
        q.selectTask(id).executeAsOne().toItem()
    }

    /** 新建下载任务：[fileUri] 应是已解析软链接的远端文件 URI */
    @Throws(Exception::class)
    suspend fun createDownload(
        accountId: String,
        fileUri: String,
        displayName: String,
        size: Long,
        destPath: String,
    ): TransferItem = io {
        val id = Uuid.random().toString()
        val ts = now()
        q.insertTask(
            id = id,
            account_id = accountId,
            kind = TransferKind.DOWNLOAD.wire,
            state = TransferState.QUEUED.wire,
            source_ref = null,
            display_name = displayName,
            size = size,
            last_modified = null,
            mime_type = null,
            target_uri = fileUri,
            dest_path = destPath,
            overwrite = 0,
            error_code = null,
            error_msg = null,
            created_at = ts,
            updated_at = ts,
        )
        q.selectTask(id).executeAsOne().toItem()
    }

    @Throws(Exception::class)
    suspend fun setState(id: String, state: TransferState, errorCode: Int? = null, errorMessage: String? = null) = io {
        q.updateState(state.wire, errorCode?.toLong(), errorMessage, now(), id)
    }

    /** 执行器上报实时进度（建议节流，如每 500ms 一次） */
    @Throws(Exception::class)
    suspend fun setBytesDone(id: String, bytesDone: Long) = io { q.updateBytesDone(bytesDone, now(), id) }

    /** 保存平台续传数据（iOS `resumeData`）；null 清除 */
    @Throws(Exception::class)
    suspend fun setResumeBlob(id: String, blob: ByteArray?) = io { q.updateResumeBlob(blob, now(), id) }

    @Throws(Exception::class)
    suspend fun resumeBlob(id: String): ByteArray? = io { q.selectTask(id).executeAsOneOrNull()?.resume_blob }

    /** 删除任务及其分片记录 */
    @Throws(Exception::class)
    suspend fun delete(id: String) = io {
        database.transaction {
            q.deleteChunks(id)
            q.deleteTask(id)
        }
    }

    /** 清除账户下已完成/已取消的任务 */
    @Throws(Exception::class)
    suspend fun clearFinished(accountId: String) = io {
        database.transaction {
            q.selectFinishedTaskIds(accountId).executeAsList().forEach { q.deleteChunks(it) }
            q.deleteFinishedTasks(accountId)
        }
    }

    // ---- 规划器内部使用 ----

    internal suspend fun row(id: String): TransferTask? = io { q.selectTask(id).executeAsOneOrNull() }

    internal suspend fun chunks(id: String): List<TransferChunk> = io { q.selectChunks(id).executeAsList() }

    internal suspend fun chunk(id: String, index: Int): TransferChunk? =
        io { q.selectChunk(id, index.toLong()).executeAsOneOrNull() }

    /** 保存新会话并重建分片表 */
    internal suspend fun saveUploadSession(
        id: String,
        sessionJson: String,
        policyType: String,
        chunkSize: Long,
        chunkCount: Int,
    ) = io {
        database.transaction {
            q.deleteChunks(id)
            q.updateSession(sessionJson, policyType, chunkSize, chunkCount.toLong(), now(), id)
            q.updateBytesDone(0, now(), id)
            repeat(chunkCount) { q.insertChunk(id, it.toLong(), ChunkState.PENDING, null, 0, 0) }
        }
    }

    internal suspend fun saveDownloadSession(id: String, sessionJson: String?) = io {
        q.updateSession(sessionJson, null, 0, 0, now(), id)
    }

    internal suspend fun clearSession(id: String) = io {
        database.transaction {
            q.deleteChunks(id)
            q.clearSession(now(), id)
        }
    }

    internal suspend fun updateChunk(id: String, index: Int, state: String, etag: String?, bytesDone: Long, attempts: Int) =
        io { q.updateChunk(state, etag, bytesDone, attempts.toLong(), id, index.toLong()) }

    internal suspend fun setChunkState(id: String, index: Int, state: String) =
        io { q.updateChunkState(state, id, index.toLong()) }

    internal suspend fun releaseRunningChunks(id: String) = io { q.releaseRunningChunks(id) }

    internal suspend fun setAttempts(id: String, attempts: Int) = io { q.updateAttempts(attempts.toLong(), now(), id) }

    /** 清零任务与全部分片的重试计数（用户手动重试/继续时） */
    internal suspend fun resetAttempts(id: String) = io {
        database.transaction {
            q.updateAttempts(0, now(), id)
            q.resetChunkAttempts(id)
        }
    }

    internal suspend fun updateTarget(id: String, displayName: String, targetUri: String, overwrite: Boolean) =
        io { q.updateTarget(displayName, targetUri, if (overwrite) 1 else 0, now(), id) }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    internal companion object {
        /** 目录 URI + 文件名（按 CrUri 规则编码） */
        fun joinUri(dirUri: String, name: String): String =
            CrUri.parse(dirUri).join(name).toString()

        fun summarize(items: List<TransferItem>): TransferSummary {
            val active = items.filter { it.state.isActive }
            return TransferSummary(
                activeUploads = active.count { it.kind == TransferKind.UPLOAD },
                activeDownloads = active.count { it.kind == TransferKind.DOWNLOAD },
                failed = items.count { it.state == TransferState.FAILED },
                bytesDone = active.sumOf { it.bytesDone.coerceAtMost(it.size) },
                bytesTotal = active.sumOf { it.size },
            )
        }
    }
}

internal fun TransferTask.toItem(): TransferItem = TransferItem(
    id = id,
    accountId = account_id,
    kind = TransferKind.fromWire(kind),
    state = TransferState.fromWire(state),
    sourceRef = source_ref,
    displayName = display_name,
    size = size,
    lastModified = last_modified,
    mimeType = mime_type,
    targetUri = target_uri,
    destPath = dest_path,
    overwrite = overwrite != 0L,
    policyType = policy_type,
    chunkSize = chunk_size,
    chunkCount = chunk_count.toInt(),
    bytesDone = bytes_done,
    errorCode = error_code?.toInt(),
    errorMessage = error_msg,
    createdAt = created_at,
    updatedAt = updated_at,
)
