package plus.ling.opencloudreve.core.transfer

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ErrorCode
import kotlin.time.TimeSource

/**
 * 在当前进程内同步执行一个传输任务（不经过平台执行器）：按 `UploadPlanner` / `DownloadPlanner` 的循环协议
 * 用账户的 Ktor 客户端收发字节。供不能依赖后台调度、需要在一次系统回调内等到结果的场景使用
 * （iOS File Provider 扩展的 `fetchContents` / `createItem` / `modifyItem`）。
 *
 * 任务状态仍由规划器写入 DB；本类只负责搬运字节并把结果交回规划器。
 * 成功返回已完成的任务；失败（含被暂停/取消）抛 [TransferFailedException]，40004 冲突抛 [UploadConflictException]。
 * 协程被取消时直接停止（调用方负责后续清理，便捷方法 [uploadFile] / [downloadFile] 已处理）。
 */
class DirectTransferRunner(
    private val repository: TransferRepository,
    private val clients: ApiClientProvider,
    private val planner: UploadPlanner,
    private val downloads: DownloadPlanner,
    private val materializer: ChunkMaterializer,
    private val fileSystem: FileSystemBridge,
) {
    // ---- 上传 ----

    /** 执行上传任务 [taskId] 直到完成或失败 */
    @Throws(Exception::class)
    suspend fun upload(taskId: String): TransferItem {
        val accountId = requireTask(taskId).accountId
        while (true) {
            planner.prepare(taskId)
            if (!pump(taskId, accountId)) break
        }
        return requireCompleted(taskId)
    }

    /**
     * 新建上传任务（目标 = [targetDirUri] + [name]）并在本进程内执行；无论结果如何都删除任务记录，
     * 被取消时先撤销服务端上传会话。[sourceRef] 见 `FileSystemBridge`（iOS 为绝对路径）。
     */
    @Throws(Exception::class)
    suspend fun uploadFile(
        accountId: String,
        sourceRef: String,
        name: String,
        size: Long,
        lastModifiedMillis: Long?,
        mimeType: String?,
        targetDirUri: String,
        overwrite: Boolean,
    ): TransferItem {
        val source = UploadSource(sourceRef, name, size, lastModifiedMillis, mimeType)
        val task = repository.createUpload(accountId, source, targetDirUri, overwrite)
        return try {
            upload(task.id)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { planner.cancel(task.id) } }
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { repository.delete(task.id) } }
        }
    }

    /** 驱动一个上传会话直到完成/失败/停止；返回 true 表示规划器要求重建会话（Restart） */
    private suspend fun pump(taskId: String, accountId: String): Boolean {
        while (true) {
            val requests = planner.nextRequests(taskId)
            if (requests.isEmpty()) {
                // 没有可发的请求：全部完成则收尾，否则已被暂停/取消/判定失败
                if (planner.allChunksDone(taskId)) planner.finalize(taskId)
                return false
            }
            val results = coroutineScope { requests.map { async { send(accountId, it) } }.awaitAll() }
            var retryAfter = 0L
            for (sent in results) {
                val outcome = when (sent) {
                    is Sent.Response -> planner.onChunkDone(taskId, sent.index, sent.response)
                    is Sent.NetworkError -> planner.onChunkFailed(taskId, sent.index, sent.message)
                    is Sent.SourceError -> {
                        repository.setState(taskId, TransferState.FAILED, TransferErrorCode.SOURCE_UNAVAILABLE, sent.message)
                        return false
                    }
                }
                when (outcome) {
                    is TransferOutcome.Continue -> if (outcome.allChunksDone) {
                        planner.finalize(taskId)
                        return false
                    }
                    is TransferOutcome.RetryAfter -> retryAfter = maxOf(retryAfter, outcome.delayMillis)
                    // 同批其余响应作废（与平台执行器取消在途请求一致），重建会话时规划器会重排分片
                    TransferOutcome.Restart -> return true
                    is TransferOutcome.Failed -> return false
                }
            }
            if (retryAfter > 0) delay(retryAfter)
        }
    }

    /** 一个分片请求的发送结果 */
    private sealed interface Sent {
        val index: Int

        data class Response(override val index: Int, val response: ChunkResponse) : Sent
        data class NetworkError(override val index: Int, val message: String) : Sent
        data class SourceError(override val index: Int, val message: String) : Sent
    }

    private suspend fun send(accountId: String, request: ChunkRequest): Sent {
        val body = try {
            materializer.readBody(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Sent.SourceError(request.index, e.message ?: "source unreadable")
        }
        return try {
            val contentType = request.headers.entries
                .firstOrNull { it.key.equals(UploadPlanner.HEADER_CONTENT_TYPE, ignoreCase = true) }
                ?.value?.let(ContentType::parse)
                ?: ContentType.Application.OctetStream
            val response = clients.client(accountId).http.request(request.url) {
                method = HttpMethod.parse(request.method)
                request.headers.forEach { (k, v) ->
                    if (!k.equals(UploadPlanner.HEADER_CONTENT_TYPE, ignoreCase = true)) header(k, v)
                }
                setBody(ByteArrayContent(body, contentType))
            }
            Sent.Response(request.index, ChunkResponse(response.status.value, response.headers["ETag"], response.bodyAsText()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Sent.NetworkError(request.index, e.message ?: e::class.simpleName ?: "network error")
        }
    }

    // ---- 下载 ----

    /** 执行下载任务 [taskId]（写到任务的 `destPath`）直到完成或失败 */
    @Throws(Exception::class)
    suspend fun download(taskId: String): TransferItem {
        val accountId = requireTask(taskId).accountId
        while (true) {
            val request = downloads.prepare(taskId)
            val outcome = try {
                val total = fetch(accountId, request)
                downloads.onCompleted(taskId, total)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: DestinationException) {
                repository.setState(taskId, TransferState.FAILED, TransferErrorCode.SOURCE_UNAVAILABLE, e.message)
                break
            } catch (e: HttpStatusException) {
                downloads.onFailed(taskId, e.status, e.message ?: "")
            } catch (e: Exception) {
                downloads.onFailed(taskId, null, e.message ?: e::class.simpleName ?: "network error")
            }
            if (outcome is TransferOutcome.RetryAfter) delay(outcome.delayMillis) else break
        }
        return requireCompleted(taskId)
    }

    /**
     * 新建下载任务（[fileUri] 应已解析软链接）并下载到 [destPath]；无论结果如何都删除任务记录，
     * 失败或取消时删除部分文件。
     */
    @Throws(Exception::class)
    suspend fun downloadFile(accountId: String, fileUri: String, displayName: String, size: Long, destPath: String): TransferItem {
        val task = repository.createDownload(accountId, fileUri, displayName, size, destPath)
        var completed = false
        return try {
            download(task.id).also { completed = true }
        } finally {
            withContext(NonCancellable) {
                if (!completed) runCatching { fileSystem.delete(destPath) }
                runCatching { repository.delete(task.id) }
            }
        }
    }

    /** 下载到 `destPath`，返回文件总长；206 从续传点追加，200 从头重写 */
    private suspend fun fetch(accountId: String, request: DownloadRequest): Long {
        val dest = request.destPath
        // 只有本地部分文件长度与规划器记录一致时才续传
        val existing = destination { fileSystem.size(dest) } ?: 0L
        val offset = if (request.resumeOffset > 0 && existing == request.resumeOffset) existing else 0L
        if (offset == 0L) destination { fileSystem.writeFile(dest, ByteArray(0)) }
        return clients.client(accountId).http.prepareRequest(request.url) {
            method = HttpMethod.Get
            request.headers.forEach { (k, v) -> if (!k.equals("Range", ignoreCase = true)) header(k, v) }
            if (offset > 0) header("Range", "bytes=$offset-")
        }.execute { response ->
            val start = when (response.status.value) {
                206 -> offset
                200 -> {
                    if (offset > 0) destination { fileSystem.writeFile(dest, ByteArray(0)) }
                    0L
                }
                else -> throw HttpStatusException(response.status.value, response.status.description)
            }
            val expected = response.contentLength()?.let { start + it }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)
            var pending = 0
            var written = start
            var lastReport = TimeSource.Monotonic.markNow()
            downloads.onProgress(request.taskId, written)
            while (true) {
                val n = channel.readAvailable(buffer, pending, buffer.size - pending)
                if (n > 0) pending += n
                if (pending == buffer.size || (n < 0 && pending > 0)) {
                    val chunk = if (pending == buffer.size) buffer.copyOf() else buffer.copyOf(pending)
                    destination { fileSystem.appendFile(dest, chunk) }
                    written += pending
                    pending = 0
                    if (lastReport.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS) {
                        lastReport = TimeSource.Monotonic.markNow()
                        downloads.onProgress(request.taskId, written)
                    }
                }
                if (n < 0) break
            }
            if (expected != null && written != expected) throw IllegalStateException("Incomplete download: $written of $expected bytes")
            written
        }
    }

    /** 本地文件读写失败（不重试） */
    private class DestinationException(cause: Throwable) : Exception(cause.message ?: "destination unwritable", cause)

    /** 存储端返回了非 200/206 */
    private class HttpStatusException(val status: Int, message: String) : Exception(message)

    private inline fun <T> destination(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DestinationException(e)
    }

    // ---- 公共 ----

    private suspend fun requireTask(taskId: String): TransferItem =
        repository.task(taskId) ?: throw IllegalArgumentException("Transfer task not found: $taskId")

    /** 任务已完成则返回，否则按 DB 中的错误抛 [TransferFailedException]（被暂停/取消、无错误码时按传输失败） */
    private suspend fun requireCompleted(taskId: String): TransferItem {
        val item = requireTask(taskId)
        if (item.state == TransferState.COMPLETED) return item
        val code = item.errorCode ?: ErrorCode.TRANSPORT
        throw TransferFailedException(taskId, code, item.errorMessage ?: "Transfer ${item.state.name.lowercase()}")
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
