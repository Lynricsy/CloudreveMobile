package plus.ling.opencloudreve.core.transfer

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ApiEnvelope
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
    /** 本地已写入的字节数（执行器应确认与部分文件长度一致）；0 表示必须从头写（截断已有内容） */
    val resumeOffset: Long,
    val headers: Map<String, String>,
    /**
     * iOS `URLSession` 的 resumeData（若之前保存过，且仍对应 [url]）。为 null 时必须用 [url] 从头下载：
     * 签名 URL 被重新获取后旧 resumeData 内嵌的是旧 URL，规划器会丢弃它。
     */
    val resumeData: ByteArray?,
)

/**
 * 一次下载响应写完后交给 [DownloadPlanner.onCompleted] 判定的结果。
 * 执行器只有在判定为完成（`Continue(true)`）后才能把文件交付给用户（发布到下载目录 / 交给 File Provider）。
 */
class DownloadResult(
    /** HTTP 状态码（200 或 206） */
    val statusCode: Int,
    /** 响应头 `Content-Type` 原样 */
    val contentType: String?,
    /** 响应头 `Content-Range` 原样（206 时用于核对续传起点） */
    val contentRange: String?,
    /** 本次请求的 `Range` 起点（无 Range 为 0）；iOS 用 resumeData 续传时不知道起点，传 null 跳过起点核对 */
    val resumeOffset: Long?,
    /** 本地文件最终长度 */
    val totalBytes: Long,
    /**
     * 本次响应体的全部字节，仅当响应体不超过 [DownloadPlanner.SMALL_BODY_LIMIT] 字节时提供（否则 null）。
     * 用于识别 Cloudreve 以 HTTP 200 返回的错误信封（如签名失效 40020）。
     */
    val smallBody: ByteArray?,
)

/** 下载任务持久化的签名 URL 及其过期时间（毫秒；null 表示不过期） */
@Serializable
internal data class DownloadSession(val url: String, val expiresAt: Long? = null)

/**
 * 下载规划器：用 `POST /file/url {uris:[uri], download:true, entity}` 取得签名 URL（过期前 60 秒重新获取），
 * 并维护进度/重试状态。字节传输由平台执行器完成（服务端 `/file/content` 支持 Range 续传；
 * 第三方直链以存储方为准）。
 *
 * 首次准备时用 `GET /file/info` 固定文件的当前版本（实体）和大小，之后续签 URL 总是请求同一实体：
 * 期间文件被覆盖也不会把两个版本的字节拼在一起。固定的实体被删除（40077）时清零进度、重新固定当前版本。
 */
class DownloadPlanner(
    private val repository: TransferRepository,
    private val clients: ApiClientProvider,
    private val clock: () -> Instant = { Clock.System.now() },
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
) {
    /**
     * 取得（必要时刷新）下载 URL，任务 → running。每次开始或重试下载前都调用一次。
     * 失败抛 [TransferFailedException]（任务已标记 failed）；任务已被暂停/取消时抛 [TransferStoppedException]（不改状态）。
     */
    @Throws(Exception::class)
    suspend fun prepare(taskId: String): DownloadRequest {
        var row = requireActiveDownload(taskId)
        val destPath = row.dest_path ?: fail(taskId, TransferErrorCode.SOURCE_UNAVAILABLE, "Missing download destination")
        // 本次才固定实体时，之前缓存的 URL 指向的版本无法确认，不再复用
        val newlyPinned = row.entity_id == null
        row = pinEntity(row)
        val cached = row.downloadSession()
            ?.takeIf { !newlyPinned }
            ?.takeIf { s -> s.expiresAt == null || s.expiresAt - now() > URL_REFRESH_MARGIN_MS }
        val session = if (cached != null) {
            cached
        } else {
            val (resolved, latest) = resolveUrl(row)
            row = latest
            repository.saveDownloadSession(taskId, CloudreveJson.encodeToString(DownloadSession.serializer(), resolved))
            resolved
        }
        if (cached == null && row.resume_blob != null) {
            // resumeData 内嵌的是旧签名 URL：URL 换了就不能再用，否则请求仍发往已失效的地址
            repository.resetDownloadProgress(taskId)
            row = repository.row(taskId) ?: row
        }
        if (!repository.transitionIfActive(taskId, TransferState.RUNNING)) throw stopped(taskId)
        val offset = row.bytes_done.coerceAtLeast(0)
        return DownloadRequest(
            taskId = taskId,
            url = session.url,
            destPath = destPath,
            resumeOffset = offset,
            headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap(),
            resumeData = row.resume_blob,
        )
    }

    /** 执行器上报已写入本地的字节数（建议节流）；有进展时清零重试计数 */
    @Throws(Exception::class)
    suspend fun onProgress(taskId: String, bytesWritten: Long) {
        val row = repository.row(taskId) ?: return
        repository.setBytesDone(taskId, bytesWritten)
        if (row.attempts != 0L && bytesWritten > row.bytes_done) repository.setAttempts(taskId, 0)
    }

    /**
     * 响应体已写完（文件在 `destPath` 或执行器的暂存位置），判定内容是否就是固定的文件实体：
     * - `Continue(true)`：完成，任务 → completed，执行器此时才可交付文件；
     * - `RetryAfter`：内容无效（Cloudreve 以 HTTP 200 返回的错误信封，如签名失效；或长度/续传区间与实体不符），
     *   已丢弃签名 URL/进度/resumeData，执行器应丢弃已写内容，等待后重新 [prepare]（将从 0 开始）；
     * - `Failed`：重试次数用尽，任务已标记 failed；
     * - `Continue(false)`：任务已被暂停/取消，执行器直接停止，不要交付文件。
     *
     * 判定以长度为准：长度等于实体大小的 200/206 视为完成，但 200 响应体恰好是 Cloudreve 错误信封时有歧义——
     * 服务端签名失败也以 HTTP 200 返回，长度可能凑巧与文件一致。此时用同一 URL 发 `Range: bytes=0-0` 探测：
     * 签名校验先于 Range 处理，错误只会再返回 200 信封；真实内容（`/file/content` 与各存储方）返回 206。
     * 只有探测为 206 才完成，探测失败或无法确认一律按“URL 被拒”重下，不交付可疑文件。
     * 长度不符时若响应体是 Cloudreve 错误信封，同样按“URL 被拒”处理（错误码取信封内的）。
     */
    @Throws(Exception::class)
    suspend fun onCompleted(taskId: String, result: DownloadResult): TransferOutcome {
        val row = repository.row(taskId) ?: return TransferOutcome.Failed(ErrorCode.NOT_FOUND, "Transfer task not found")
        if (row.state != TransferState.RUNNING.wire) return TransferOutcome.Continue(false)
        if (result.statusCode != 200 && result.statusCode != 206) {
            return onFailed(taskId, result.statusCode, "Unexpected status ${result.statusCode}")
        }
        val expected = row.size
        val rangeStart = if (result.statusCode == 206) contentRangeStart(result.contentRange) else 0L
        val rangeOk = result.resumeOffset == null || rangeStart == null || rangeStart == result.resumeOffset
        val ambiguous = result.statusCode == 200 && errorEnvelope(result) != null
        if (rangeOk && result.totalBytes == expected && (!ambiguous || servesRealContent(row))) {
            if (!repository.transitionIfActive(taskId, TransferState.COMPLETED)) return TransferOutcome.Continue(false)
            repository.setBytesDone(taskId, result.totalBytes)
            repository.setResumeBlob(taskId, null)
            repository.saveDownloadSession(taskId, null)
            repository.setAttempts(taskId, 0)
            return TransferOutcome.Continue(true)
        }
        // 内容不可用：从 0 重新下载
        repository.resetDownloadProgress(taskId)
        val envelope = errorEnvelope(result)
        return if (envelope != null) {
            repository.saveDownloadSession(taskId, null)
            retryOrFail(row, envelope.code, "Download URL rejected: ${envelope.code} ${envelope.msg}".trim(), retryable = true)
        } else {
            val text = if (!rangeOk) {
                "Content-Range ${result.contentRange} does not start at ${result.resumeOffset}"
            } else {
                "Downloaded ${result.totalBytes} bytes, expected $expected"
            }
            retryOrFail(row, TransferErrorCode.STORAGE_REJECTED, text, retryable = true)
        }
    }

    /**
     * 下载失败：[httpStatus] 为 null 表示网络错误。返回 [TransferOutcome.RetryAfter]（等待后重新 [prepare]）或
     * [TransferOutcome.Failed]。URL 过期/被拒（401/403/410）会丢弃缓存的 URL 和内嵌该 URL 的 resumeData
     * （有 resumeData 时进度也随之作废）；416 从 0 重新下载。
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
                // iOS 的进度在 resumeData 里：丢弃 resumeData 即从 0 开始；Android 的部分文件按 Range 续传仍然有效
                if (row.resume_blob != null) repository.resetDownloadProgress(taskId)
                true
            }
            416 -> {
                repository.resetDownloadProgress(taskId)
                true
            }
            else -> false
        }
        val code = if (httpStatus == null) ErrorCode.TRANSPORT else TransferErrorCode.STORAGE_REJECTED
        val text = if (httpStatus == null) message else "HTTP $httpStatus: $message"
        return retryOrFail(row, code, text, retryable)
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

    private suspend fun retryOrFail(row: TransferTask, code: Int, text: String, retryable: Boolean): TransferOutcome {
        val attempts = row.attempts.toInt() + 1
        val wait = if (retryable) retryPolicy.delayFor(attempts) else null
        if (wait == null) {
            repository.setState(row.id, TransferState.FAILED, code, text)
            return TransferOutcome.Failed(code, text)
        }
        repository.setAttempts(row.id, attempts)
        return TransferOutcome.RetryAfter(wait)
    }

    /**
     * 首次准备时固定文件的当前版本（实体）与大小。固定之前写下的进度无法确认属于哪个版本，一并作废。
     * 空文件没有实体，记为 ''（不按实体请求 URL）。
     */
    private suspend fun pinEntity(row: TransferTask): TransferTask {
        if (row.entity_id != null) return row
        val api = FileApi(clients.client(row.account_id))
        val file = try {
            retryPolicy.retryTransport { api.info(uri = row.target_uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            fail(row.id, e.code, e.serverMsg, e)
        } catch (e: TransportException) {
            fail(row.id, e.code, e.message ?: "network error", e)
        }
        val entity = file.primaryEntity?.takeIf { file.size > 0 }.orEmpty()
        if (row.bytes_done > 0 || row.resume_blob != null) repository.resetDownloadProgress(row.id)
        repository.setDownloadPin(row.id, entity, file.size)
        return repository.row(row.id) ?: row
    }

    /**
     * 用任务当前的签名 URL 发一个 1 字节的 Range 请求：返回 206 说明 URL 有效、响应是真实内容。
     * 没有 URL、请求失败或返回其它状态都视为无法确认（返回 false）。
     */
    private suspend fun servesRealContent(row: TransferTask): Boolean {
        val url = row.downloadSession()?.url ?: return false
        return try {
            clients.client(row.account_id).http.prepareGet(url) {
                header(HttpHeaders.Range, "bytes=0-0")
            }.execute { it.status.value == 206 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求固定实体的签名 URL；实体已被删除（40077）时（[repin] 为真）重新固定当前版本、清零进度后再请求一次。
     * 返回 URL 与最新的任务行。
     */
    private suspend fun resolveUrl(row: TransferTask, repin: Boolean = true): Pair<DownloadSession, TransferTask> {
        val entity = row.entity_id?.takeIf { it.isNotEmpty() }
        val api = FileApi(clients.client(row.account_id))
        val response = try {
            retryPolicy.retryTransport {
                api.url(FileUrlRequest(uris = listOf(row.target_uri), download = true, entity = entity))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 单个 uri 的 40081：拆出内层错误
            val inner = e.aggregated?.values?.firstOrNull()
            val code = inner?.code ?: e.code
            if (code == ENTITY_NOT_EXIST && entity != null && repin) {
                // 固定的版本已被删除：已下载的字节属于旧版本，作废后改下当前版本
                repository.setDownloadPin(row.id, null, row.size)
                repository.resetDownloadProgress(row.id)
                val repinned = pinEntity(repository.row(row.id) ?: row)
                if (repinned.entity_id != entity) return resolveUrl(repinned, repin = false)
            }
            val msg = inner?.msg?.takeIf { it.isNotEmpty() } ?: e.serverMsg
            fail(row.id, code, msg, e)
        } catch (e: TransportException) {
            fail(row.id, e.code, e.message ?: "network error", e)
        }
        val url = response.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
            ?: fail(row.id, ErrorCode.FILE_NOT_FOUND, "No download url returned")
        return DownloadSession(url, response.expires?.toEpochMilliseconds()) to row
    }

    private suspend fun requireActiveDownload(taskId: String): TransferTask {
        val row = repository.row(taskId) ?: throw IllegalArgumentException("Transfer task not found: $taskId")
        require(row.kind == TransferKind.DOWNLOAD.wire) { "Not a download task: $taskId" }
        val state = TransferState.fromWire(row.state)
        if (!state.isActive) throw TransferStoppedException(taskId, state)
        return row
    }

    private suspend fun stopped(taskId: String): TransferStoppedException {
        val state = repository.row(taskId)?.state?.let(TransferState::fromWire) ?: TransferState.CANCELED
        return TransferStoppedException(taskId, state)
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

        /** [DownloadResult.smallBody] 的上限：Cloudreve 错误信封远小于它 */
        const val SMALL_BODY_LIMIT = 4096

        /** 服务端 `CodeEntityNotExist`：请求的实体（版本）不存在 */
        private const val ENTITY_NOT_EXIST = 40077

        /** 响应体是 Cloudreve 错误信封（JSON、`code != 0`）时返回它 */
        internal fun errorEnvelope(result: DownloadResult): ApiEnvelope? {
            val body = result.smallBody?.takeIf { it.size <= SMALL_BODY_LIMIT } ?: return null
            val type = result.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (type != "application/json") return null
            val env = runCatching { CloudreveJson.decodeFromString(ApiEnvelope.serializer(), body.decodeToString()) }.getOrNull()
            return env?.takeIf { it.code != 0 }
        }

        /** `Content-Range: bytes <start>-<end>/<total>` 的起点；无法解析返回 null */
        internal fun contentRangeStart(contentRange: String?): Long? =
            contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()
    }
}
