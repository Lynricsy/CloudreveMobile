package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.api.endpoints.FileApi
import plus.ling.opencloudreve.core.db.TransferTask
import plus.ling.opencloudreve.core.model.FileUrlRequest
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 执行器要执行的一次下载。[headers] 已包含续传用的 `Range: bytes=<resumeOffset>-`。
 * 续传时若服务器返回 200 而不是 206（不支持 Range），执行器应截断本地文件并从 0 写入。
 */
class DownloadRequest(
    val taskId: String,
    val url: String,
    val destPath: String,
    /** 本地已写入的字节数（执行器应确认与部分文件长度一致） */
    val resumeOffset: Long,
    val headers: Map<String, String>,
    /** iOS `URLSession` 的 resumeData（若之前保存过） */
    val resumeData: ByteArray?,
)

/** 下载任务持久化的签名 URL 及其过期时间（毫秒；null 表示不过期） */
@Serializable
internal data class DownloadSession(val url: String, val expiresAt: Long? = null)

/**
 * 下载规划器：用 `POST /file/url {uris:[uri], download:true}` 取得签名 URL（过期前 60 秒重新获取），
 * 并维护进度/重试状态。字节传输由平台执行器完成（服务端 `/file/content` 支持 Range 续传；
 * 第三方直链以存储方为准）。
 */
class DownloadPlanner(
    private val repository: TransferRepository,
    private val clients: ApiClientProvider,
    private val clock: () -> Instant = { Clock.System.now() },
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
) {
    /**
     * 取得（必要时刷新）下载 URL，任务 → running。每次开始或重试下载前都调用一次。
     * 失败抛 [TransferFailedException]（任务已标记 failed）。
     */
    @Throws(Exception::class)
    suspend fun prepare(taskId: String): DownloadRequest {
        val row = requireDownload(taskId)
        val destPath = row.dest_path ?: fail(taskId, TransferErrorCode.SOURCE_UNAVAILABLE, "Missing download destination")
        val cached = row.downloadSession()?.takeIf { s -> s.expiresAt == null || s.expiresAt - now() > URL_REFRESH_MARGIN_MS }
        val session = cached ?: resolveUrl(row).also {
            repository.saveDownloadSession(taskId, CloudreveJson.encodeToString(DownloadSession.serializer(), it))
        }
        repository.setState(taskId, TransferState.RUNNING)
        val offset = row.bytes_done.coerceAtLeast(0)
        return DownloadRequest(
            taskId = taskId,
            url = session.url,
            destPath = destPath,
            resumeOffset = offset,
            headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap(),
            resumeData = repository.resumeBlob(taskId),
        )
    }

    /** 执行器上报已写入本地的字节数（建议节流）；有进展时清零重试计数 */
    @Throws(Exception::class)
    suspend fun onProgress(taskId: String, bytesWritten: Long) {
        val row = repository.row(taskId) ?: return
        repository.setBytesDone(taskId, bytesWritten)
        if (row.attempts != 0L && bytesWritten > row.bytes_done) repository.setAttempts(taskId, 0)
    }

    /** 下载完成（文件已写到 `destPath`） */
    @Throws(Exception::class)
    suspend fun onCompleted(taskId: String, totalBytes: Long) {
        repository.setBytesDone(taskId, totalBytes)
        repository.setResumeBlob(taskId, null)
        repository.saveDownloadSession(taskId, null)
        repository.setAttempts(taskId, 0)
        repository.setState(taskId, TransferState.COMPLETED)
    }

    /**
     * 下载失败：[httpStatus] 为 null 表示网络错误。返回 [TransferOutcome.RetryAfter]（等待后重新 [prepare]）或
     * [TransferOutcome.Failed]。URL 过期/被拒（401/403/410）会丢弃缓存的 URL；416 从 0 重新下载。
     * 任务已不在 running（被暂停/取消）时返回 `Continue(false)`，执行器直接停止即可。
     */
    @Throws(Exception::class)
    suspend fun onFailed(taskId: String, httpStatus: Int?, message: String): TransferOutcome {
        val row = repository.row(taskId) ?: return TransferOutcome.Failed(ErrorCode.NOT_FOUND, message)
        if (row.state != TransferState.RUNNING.wire) return TransferOutcome.Continue(false)
        val retryable = when (httpStatus) {
            null, 408, 429, in 500..599 -> true
            401, 403, 410 -> {
                repository.saveDownloadSession(taskId, null)
                true
            }
            416 -> {
                repository.setBytesDone(taskId, 0)
                repository.setResumeBlob(taskId, null)
                true
            }
            else -> false
        }
        val code = if (httpStatus == null) ErrorCode.TRANSPORT else TransferErrorCode.STORAGE_REJECTED
        val text = if (httpStatus == null) message else "HTTP $httpStatus: $message"
        val attempts = row.attempts.toInt() + 1
        val wait = if (retryable) retryPolicy.delayFor(attempts) else null
        if (wait == null) {
            repository.setState(taskId, TransferState.FAILED, code, text)
            return TransferOutcome.Failed(code, text)
        }
        repository.setAttempts(taskId, attempts)
        return TransferOutcome.RetryAfter(wait)
    }

    /** 暂停：保留进度与 resumeData，任务 → paused（执行器应先停止下载并保存 resumeData） */
    @Throws(Exception::class)
    suspend fun pause(taskId: String) = repository.setState(taskId, TransferState.PAUSED)

    /** 取消：任务 → canceled（执行器负责删除部分文件） */
    @Throws(Exception::class)
    suspend fun cancel(taskId: String) {
        repository.setResumeBlob(taskId, null)
        repository.saveDownloadSession(taskId, null)
        repository.setState(taskId, TransferState.CANCELED)
    }

    private suspend fun resolveUrl(row: TransferTask): DownloadSession {
        val api = FileApi(clients.client(row.account_id))
        val response = try {
            retryPolicy.retryTransport { api.url(FileUrlRequest(uris = listOf(row.target_uri), download = true)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 单个 uri 的 40081：拆出内层错误
            val inner = e.aggregated?.values?.firstOrNull()
            val code = inner?.code ?: e.code
            val msg = inner?.msg?.takeIf { it.isNotEmpty() } ?: e.serverMsg
            fail(row.id, code, msg, e)
        } catch (e: TransportException) {
            fail(row.id, e.code, e.message ?: "network error", e)
        }
        val url = response.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
            ?: fail(row.id, ErrorCode.FILE_NOT_FOUND, "No download url returned")
        return DownloadSession(url, response.expires?.toEpochMilliseconds())
    }

    private suspend fun requireDownload(taskId: String): TransferTask {
        val row = repository.row(taskId) ?: throw IllegalArgumentException("Transfer task not found: $taskId")
        require(row.kind == TransferKind.DOWNLOAD.wire) { "Not a download task: $taskId" }
        return row
    }

    private suspend fun fail(taskId: String, code: Int, message: String, cause: Throwable? = null): Nothing {
        repository.setState(taskId, TransferState.FAILED, code, message)
        throw TransferFailedException(taskId, code, message, cause)
    }

    private fun now(): Long = clock().toEpochMilliseconds()

    private fun TransferTask.downloadSession(): DownloadSession? = session_json?.let {
        runCatching { CloudreveJson.decodeFromString(DownloadSession.serializer(), it) }.getOrNull()
    }

    companion object {
        /** URL 剩余有效期小于该值时重新获取 */
        const val URL_REFRESH_MARGIN_MS = 60_000L
    }
}
