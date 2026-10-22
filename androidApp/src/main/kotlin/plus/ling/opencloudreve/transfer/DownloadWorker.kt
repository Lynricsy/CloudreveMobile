package plus.ling.opencloudreve.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.transfer.DownloadPlanner
import plus.ling.opencloudreve.core.transfer.DownloadRequest
import plus.ling.opencloudreve.core.transfer.DownloadResult
import plus.ling.opencloudreve.core.transfer.TransferErrorCode
import plus.ling.opencloudreve.core.transfer.TransferFailedException
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferOutcome
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.TransferStoppedException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 下载 Work：`DownloadPlanner.prepare` 取签名 URL → GET（`Range` 续传）→ 写入 [DownloadTargets] 目标。
 * 续传偏移以目标文件的实际长度为准（可能比上次上报的进度多写了一些）；服务器返回 200 时从 0 重写。
 * 断网时任务退回 queued，Work 以 retry 结束，网络恢复后从已写入处继续。
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repository: TransferRepository = CoreGraph.transferRepository()
    private val downloads: DownloadPlanner = CoreGraph.downloadPlanner()
    private val notifications = TransferNotifications(applicationContext)
    private var lastNotify = 0L
    private var lastReport = 0L

    /** 非 2xx 响应 */
    private class HttpStatusException(val status: Int, message: String) : Exception(message)

    /** 本地目标不可写（被删除、存储已满等） */
    private class DestinationException(cause: Throwable) : Exception(cause.message ?: cause.javaClass.simpleName, cause)

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AndroidTransferExecutor.KEY_TASK_ID) ?: return Result.failure()
        val item = repository.task(taskId) ?: return Result.success()
        if (item.kind != TransferKind.DOWNLOAD || !item.state.isActive) return Result.success()
        promote(notifications.foreground(item, item.bytesDone, waiting = true))
        return try {
            run(item)
        } catch (e: CancellationException) {
            requeueIfNetworkStop(this, repository, taskId)
            throw e
        }
    }

    private suspend fun run(initial: TransferItem): Result {
        val taskId = initial.id
        while (true) {
            val request = try {
                downloads.prepare(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferStoppedException) {
                // 已被暂停/取消：状态由 TransferManager 写入，这里直接结束
                return Result.success()
            } catch (e: TransferFailedException) {
                if (e.code == ErrorCode.TRANSPORT && TransferRuntime.isOffline(applicationContext)) return waitForNetwork(taskId)
                return finish(taskId)
            }
            // prepare 固定实体时会把任务大小更新为实体大小
            val item = repository.task(taskId) ?: return Result.success()
            val outcome = try {
                val result = download(item, request)
                val verdict = downloads.onCompleted(taskId, result)
                if (verdict is TransferOutcome.Continue && verdict.allChunksDone) {
                    // 规划器确认内容就是固定的实体后才交付给用户
                    try {
                        DownloadTargets.publish(applicationContext, request.destPath)
                    } catch (e: Exception) {
                        throw DestinationException(e)
                    }
                }
                verdict
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                downloads.onFailed(taskId, e.status, e.message ?: "")
            } catch (e: DestinationException) {
                repository.setState(taskId, TransferState.FAILED, TransferErrorCode.SOURCE_UNAVAILABLE, e.message)
                return finish(taskId)
            } catch (e: IOException) {
                if (TransferRuntime.isOffline(applicationContext)) return waitForNetwork(taskId)
                downloads.onFailed(taskId, null, e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                // 内容无效（如以 200 返回的签名失效信封）时规划器已清零进度，下次 prepare 从 0 截断重下
                is TransferOutcome.RetryAfter -> delay(outcome.delayMillis)
                else -> return finish(taskId)
            }
        }
    }

    /** 写入目标并返回交给规划器判定的结果（不发布：判定完成后才发布） */
    private suspend fun download(item: TransferItem, request: DownloadRequest): DownloadResult = withContext(Dispatchers.IO) {
        val pfd = try {
            DownloadTargets.open(applicationContext, request.destPath)
        } catch (e: Exception) {
            throw DestinationException(e)
        }
        pfd.use { fd ->
            FileOutputStream(fd.fileDescriptor).use { out ->
                // 规划器从 0 开始（首次、416 或内容无效后）时清空；否则以实际文件长度续传
                val offset = if (request.resumeOffset <= 0) 0L else fd.statSize.coerceAtLeast(0)
                if (offset == 0L) out.channel.truncate(0)
                val builder = Request.Builder().url(request.url).get()
                request.headers.filterKeys { !it.equals("Range", ignoreCase = true) }.forEach { (k, v) -> builder.header(k, v) }
                if (offset > 0) builder.header("Range", "bytes=$offset-")
                val call = TransferRuntime.http.newCall(builder.build())
                // 读响应体是阻塞调用：协程取消（暂停/网络约束不满足）时由此取消请求，使读取立即失败
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.await().use { response -> writeBody(item, response, out, offset) }
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    /**
     * 校验状态码并把响应体写到 [out]（206 从 [resumeOffset] 续写；200 从头重写）。
     * 返回的 [DownloadResult.resumeOffset] 是本次实际写入的起点（200 为 0）。
     */
    private suspend fun writeBody(item: TransferItem, response: Response, out: FileOutputStream, resumeOffset: Long): DownloadResult {
        val channel = out.channel
        val offset = when (response.code) {
            206 -> resumeOffset
            200 -> {
                // 不支持 Range：从头重写
                if (resumeOffset > 0) channel.truncate(0)
                0L
            }
            else -> throw HttpStatusException(response.code, response.message)
        }
        channel.position(offset)
        val expected = response.body.contentLength().takeIf { it >= 0 }?.let { offset + it }
        var written = offset
        report(item, written, force = true)
        // 小响应体留一份给规划器识别以 200 返回的 Cloudreve 错误信封
        var head: ByteArray? = ByteArray(0)
        val buffer = ByteArray(BUFFER_SIZE)
        response.body.byteStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw DestinationException(e)
                }
                head = head?.let { h -> (h + buffer.copyOf(n)).takeIf { it.size <= DownloadPlanner.SMALL_BODY_LIMIT } }
                written += n
                report(item, written)
            }
        }
        if (expected != null && written != expected) throw IOException("Incomplete download: $written of $expected bytes")
        try {
            out.fd.sync()
        } catch (_: Exception) {
            // 部分文件系统（FUSE）不支持 sync，数据仍已写入
        }
        return DownloadResult(
            statusCode = response.code,
            contentType = response.header("Content-Type"),
            contentRange = response.header("Content-Range"),
            resumeOffset = offset,
            totalBytes = written,
            smallBody = head,
        )
    }

    /** 节流上报进度（写库 + 更新通知） */
    private suspend fun report(item: TransferItem, written: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastReport >= REPORT_INTERVAL_MS) {
            lastReport = now
            downloads.onProgress(item.id, written)
        }
        if (force || now - lastNotify >= NOTIFY_INTERVAL_MS) {
            lastNotify = now
            promote(notifications.foreground(item, written))
        }
    }

    private suspend fun finish(taskId: String): Result {
        repository.task(taskId)?.let { notifications.finished(it) }
        return Result.success()
    }

    private suspend fun waitForNetwork(taskId: String): Result {
        if (repository.task(taskId)?.state?.let { it.isActive || it == TransferState.FAILED } == true) {
            repository.setState(taskId, TransferState.QUEUED)
        }
        return Result.retry()
    }

    private suspend fun promote(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val REPORT_INTERVAL_MS = 500L
        const val NOTIFY_INTERVAL_MS = 1000L
    }
}
