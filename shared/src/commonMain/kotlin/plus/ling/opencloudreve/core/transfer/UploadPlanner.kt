package plus.ling.opencloudreve.core.transfer

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ApiEnvelope
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.db.TransferChunk
import plus.ling.opencloudreve.core.db.TransferTask
import plus.ling.opencloudreve.core.model.CreateUploadSessionRequest
import plus.ling.opencloudreve.core.model.OneDriveErrorResponse
import plus.ling.opencloudreve.core.model.OneDriveRangeStatus
import plus.ling.opencloudreve.core.model.QiniuChunkResponse
import plus.ling.opencloudreve.core.model.QiniuFinishRequest
import plus.ling.opencloudreve.core.model.QiniuPart
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.UploadSession
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 上传规划器：只负责“该发什么请求、结果意味着什么”，不搬运分片字节（由平台执行器完成）。
 *
 * 执行器循环（详见 `local://p2-transfer-surface.md`）：
 * 1. [prepare]：创建或复用上传会话（状态 → running）。
 * 2. 反复 [nextRequests] 取得可立即发送的请求（已按策略并发数限流、标记为 running），
 *    用 [ChunkMaterializer] 取请求体发送，结果交给 [onChunkDone] / 网络异常交给 [onChunkFailed]，按 [TransferOutcome] 推进。
 * 3. 某次结果 `Continue(allChunksDone = true)` 后调用 [finalize]（状态 → completed）。
 *
 * 逐策略请求规则见 `ocr-spec-upload.md §2`（权威参考 Web `Uploader/core/api/index.ts`）。
 */
class UploadPlanner(
    private val repository: TransferRepository,
    private val clients: ApiClientProvider,
    private val fileSystem: FileSystemBridge,
    private val clock: () -> Instant = { Clock.System.now() },
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
) {
    private val locksGuard = Mutex()
    private val locks = HashMap<String, Mutex>()

    /**
     * 创建上传会话（`PUT /file/upload`）并初始化分片表；已有可续传会话时直接复用（不重新创建）。
     * 40004 抛 [UploadConflictException]；其它失败抛 [TransferFailedException]（任务均已标记 failed）。
     */
    @Throws(Exception::class)
    suspend fun prepare(taskId: String): TransferItem = withTaskLock(taskId) {
        val row = requireUpload(taskId)
        val existing = row.session()
        if (existing != null) {
            if (isResumable(row.policy_type, existing.expires)) {
                repository.releaseRunningChunks(taskId)
                repository.setState(taskId, TransferState.RUNNING)
                return@withTaskLock repository.task(taskId)!!
            }
            discardSession(row, existing)
        }
        repository.setState(taskId, TransferState.PREPARING)

        val sourceRef = row.source_ref ?: fail(taskId, TransferErrorCode.SOURCE_UNAVAILABLE, "Missing upload source")
        val actualSize = try {
            fileSystem.size(sourceRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: fail(taskId, TransferErrorCode.SOURCE_UNAVAILABLE, "Upload source is not readable")
        if (actualSize != row.size) {
            fail(taskId, TransferErrorCode.SOURCE_UNAVAILABLE, "Upload source changed: expected ${row.size} bytes, got $actualSize")
        }

        val api = UploadApi(client(row))
        val request = CreateUploadSessionRequest(
            uri = row.target_uri,
            size = row.size,
            lastModified = row.last_modified?.takeIf { it > 0 },
            mimeType = row.mime_type,
            entityType = if (row.overwrite != 0L) CreateUploadSessionRequest.ENTITY_TYPE_VERSION else null,
            encryptionSupported = listOf(CreateUploadSessionRequest.CIPHER_AES_256_CTR),
        )
        val session = try {
            retryPolicy.retryTransport { api.createSession(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            repository.setState(taskId, TransferState.FAILED, e.code, e.serverMsg)
            if (e.code == ErrorCode.OBJECT_EXIST) throw UploadConflictException(taskId, e.serverMsg)
            throw TransferFailedException(taskId, e.code, e.serverMsg, e)
        } catch (e: TransportException) {
            fail(taskId, e.code, e.message ?: "network error", e)
        }

        val type = effectivePolicyType(session)
        if (type !in SUPPORTED_POLICIES) {
            bestEffortDelete(api, session)
            fail(taskId, TransferErrorCode.UNSUPPORTED_POLICY, "Unsupported storage policy: $type")
        }
        if (type == StoragePolicy.TYPE_ONEDRIVE && row.size == 0L) {
            bestEffortDelete(api, session)
            fail(taskId, TransferErrorCode.ONEDRIVE_EMPTY_FILE, "OneDrive does not accept empty files")
        }
        val chunkCount = if (type == StoragePolicy.TYPE_UPYUN) 1 else chunkCount(row.size, session.chunkSize)
        repository.saveUploadSession(
            id = taskId,
            sessionJson = CloudreveJson.encodeToString(UploadSession.serializer(), session),
            policyType = type,
            chunkSize = if (type == StoragePolicy.TYPE_UPYUN) 0 else session.chunkSize,
            chunkCount = chunkCount,
        )
        repository.setState(taskId, TransferState.RUNNING)
        repository.task(taskId)!!
    }

    /**
     * 取得现在可以发出的请求（已标记为 running）。并发数 = `storage_policy.chunk_concurrency ?: 1`，
     * OneDrive 恒为 1 且按序号顺序。任务不在 running 状态、没有空闲并发或全部完成时返回空列表。
     * 指向 Cloudreve 主站的请求在生成时取有效 access token（剩余 < 10 分钟会先刷新）。
     */
    @Throws(Exception::class)
    suspend fun nextRequests(taskId: String): List<ChunkRequest> = withTaskLock(taskId) {
        val row = repository.row(taskId) ?: return@withTaskLock emptyList()
        if (row.kind != TransferKind.UPLOAD.wire || row.state != TransferState.RUNNING.wire) return@withTaskLock emptyList()
        val session = row.session() ?: return@withTaskLock emptyList()
        val chunks = repository.chunks(taskId)
        val running = chunks.count { it.state == ChunkState.RUNNING }
        val type = row.policy_type.orEmpty()
        val limit = concurrency(type, session) - running
        if (limit <= 0) return@withTaskLock emptyList()
        val pending = chunks.filter { it.state == ChunkState.PENDING }
        val picks = if (type == StoragePolicy.TYPE_ONEDRIVE) {
            // 顺序：只有前面全部完成时才发下一片
            pending.take(1).filter { first -> chunks.all { it.idx >= first.idx || it.state == ChunkState.DONE } }
        } else {
            pending.take(limit)
        }
        if (picks.isEmpty()) return@withTaskLock emptyList()
        val token = mainSiteToken(row, type)
        picks.map { chunk ->
            repository.setChunkState(taskId, chunk.idx.toInt(), ChunkState.RUNNING)
            buildRequest(row, session, chunk, token)
        }
    }

    /**
     * 重新生成某个分片的请求描述（不改变状态），供 [ChunkMaterializer] 或执行器在 token 过期后重建请求头。
     */
    @Throws(Exception::class)
    suspend fun requestFor(taskId: String, index: Int): ChunkRequest = withTaskLock(taskId) {
        val row = requireUpload(taskId)
        val session = row.session() ?: throw IllegalStateException("Upload session not prepared: $taskId")
        val chunk = repository.chunk(taskId, index) ?: throw IllegalArgumentException("No chunk $index in $taskId")
        buildRequest(row, session, chunk, mainSiteToken(row, row.policy_type.orEmpty()))
    }

    /** 执行器收到分片响应（任意 HTTP 状态）后调用 */
    @Throws(Exception::class)
    suspend fun onChunkDone(taskId: String, index: Int, response: ChunkResponse): TransferOutcome = withTaskLock(taskId) {
        val row = repository.row(taskId) ?: return@withTaskLock TransferOutcome.Continue(false)
        if (row.state != TransferState.RUNNING.wire) return@withTaskLock TransferOutcome.Continue(false)
        val session = row.session() ?: return@withTaskLock TransferOutcome.Continue(false)
        val chunk = repository.chunk(taskId, index) ?: return@withTaskLock TransferOutcome.Continue(false)
        when (val verdict = classify(row, session, response)) {
            is Verdict.Done -> markChunkDone(row, chunk, verdict.etag)
            is Verdict.Retry -> retryChunk(row, session, chunk, verdict.code, verdict.message, verdict.delayMillis)
            is Verdict.Fail -> failTask(row, session, verdict.code, verdict.message)
            Verdict.Restart -> restartTask(row, session)
            Verdict.Realign -> realignOneDrive(row, session, chunk)
        }
    }

    /** 执行器发送分片时遇到网络错误（没有 HTTP 响应）时调用 */
    @Throws(Exception::class)
    suspend fun onChunkFailed(taskId: String, index: Int, message: String): TransferOutcome = withTaskLock(taskId) {
        val row = repository.row(taskId) ?: return@withTaskLock TransferOutcome.Continue(false)
        if (row.state != TransferState.RUNNING.wire) return@withTaskLock TransferOutcome.Continue(false)
        val session = row.session() ?: return@withTaskLock TransferOutcome.Continue(false)
        val chunk = repository.chunk(taskId, index) ?: return@withTaskLock TransferOutcome.Continue(false)
        if (sessionExpired(session)) return@withTaskLock restartTask(row, session)
        retryChunk(row, session, chunk, ErrorCode.TRANSPORT, message, null)
    }

    /** 全部分片完成后调用：发送完成请求与客户端回调（按策略），成功后任务 → completed */
    @Throws(Exception::class)
    suspend fun finalize(taskId: String): TransferItem = withTaskLock(taskId) {
        val row = requireUpload(taskId)
        if (row.state == TransferState.COMPLETED.wire) return@withTaskLock row.toItem()
        val session = row.session() ?: throw IllegalStateException("Upload session not prepared: $taskId")
        val chunks = repository.chunks(taskId)
        check(chunks.isNotEmpty() && chunks.all { it.state == ChunkState.DONE }) { "Chunks of $taskId are not all uploaded" }
        repository.setState(taskId, TransferState.FINISHING)
        val type = row.policy_type.orEmpty()
        val client = client(row)
        try {
            when (type) {
                StoragePolicy.TYPE_S3, StoragePolicy.TYPE_KS3, StoragePolicy.TYPE_OBS ->
                    completeMultipart(client, session, partsXml(chunks), emptyMap())
                StoragePolicy.TYPE_COS ->
                    completeMultipart(client, session, partsXml(chunks), mapOf("x-cos-forbid-overwrite" to "true"))
                StoragePolicy.TYPE_OSS ->
                    completeMultipart(
                        client, session, "",
                        mapOf("x-oss-forbid-overwrite" to "true", "x-oss-complete-all" to "yes"),
                    )
                StoragePolicy.TYPE_QINIU -> completeQiniu(client, session, chunks)
            }
            if (type in UploadApi.CLIENT_CALLBACK_POLICIES) {
                retryPolicy.retryTransport {
                    UploadApi(client).callback(type, session.sessionId, session.callbackSecret)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageRejectedException) {
            failTask(row, session, TransferErrorCode.STORAGE_REJECTED, e.message ?: "storage rejected")
            throw TransferFailedException(taskId, TransferErrorCode.STORAGE_REJECTED, e.message ?: "storage rejected", e)
        } catch (e: ApiException) {
            failTask(row, session, e.code, e.serverMsg)
            throw TransferFailedException(taskId, e.code, e.serverMsg, e)
        } catch (e: Exception) {
            val code = (e as? TransportException)?.code ?: ErrorCode.TRANSPORT
            failTask(row, session, code, e.message ?: "network error")
            throw TransferFailedException(taskId, code, e.message ?: "network error", e)
        }
        repository.clearSession(taskId)
        repository.setAttempts(taskId, 0)
        repository.setBytesDone(taskId, row.size)
        repository.setState(taskId, TransferState.COMPLETED)
        repository.task(taskId)!!
    }

    /** 取消：`DELETE /file/upload {id, uri}`（尽力而为），清理会话，任务 → canceled。执行器应先中止在途请求 */
    @Throws(Exception::class)
    suspend fun cancel(taskId: String) = withTaskLock(taskId) {
        val row = repository.row(taskId) ?: return@withTaskLock
        val session = row.session()
        if (session != null) bestEffortDelete(UploadApi(client(row)), session)
        repository.clearSession(taskId)
        repository.setState(taskId, TransferState.CANCELED)
    }

    /** 暂停：保留会话与分片进度，在途分片退回 pending，任务 → paused。执行器应先中止在途请求 */
    @Throws(Exception::class)
    suspend fun pause(taskId: String) = withTaskLock(taskId) {
        repository.releaseRunningChunks(taskId)
        repository.setState(taskId, TransferState.PAUSED)
    }

    /** 丢弃当前会话（尽力删除服务端占位）但不改变任务状态；改名/覆盖重试前使用 */
    @Throws(Exception::class)
    suspend fun discardSession(taskId: String) = withTaskLock(taskId) {
        val row = repository.row(taskId) ?: return@withTaskLock
        val session = row.session()
        if (session != null) discardSession(row, session) else repository.clearSession(taskId)
    }

    /**
     * 把标记为 running 但实际已不在途的分片退回 pending（进程被杀后 `reattach` 用）。
     * [inFlight] 为平台仍在执行的分片序号（如 iOS `getAllTasks` 解析出的 index）。
     */
    @Throws(Exception::class)
    suspend fun releaseChunks(taskId: String, inFlight: List<Int> = emptyList()) = withTaskLock(taskId) {
        repository.chunks(taskId)
            .filter { it.state == ChunkState.RUNNING && it.idx.toInt() !in inFlight }
            .forEach { repository.setChunkState(taskId, it.idx.toInt(), ChunkState.PENDING) }
    }

    /** 是否所有分片都已确认（执行器 reattach 时据此决定直接 [finalize]） */
    @Throws(Exception::class)
    suspend fun allChunksDone(taskId: String): Boolean {
        val chunks = repository.chunks(taskId)
        return chunks.isNotEmpty() && chunks.all { it.state == ChunkState.DONE }
    }

    /** 任务当前会话是否可断点续传（会话未过期且策略支持续传） */
    @Throws(Exception::class)
    suspend fun isResumable(taskId: String): Boolean {
        val row = repository.row(taskId) ?: return false
        val session = row.session() ?: return false
        return isResumable(row.policy_type, session.expires)
    }

    /** 会话过期时间 > 现在，且策略属于可续传集合 */
    fun isResumable(policyType: String?, sessionExpires: Long): Boolean =
        policyType in RESUMABLE_POLICIES && sessionExpires > clock().epochSeconds

    // ---- 内部 ----

    /** 加密参数（key, iv）；会话未要求客户端加密时为 null */
    internal suspend fun encryptionFor(taskId: String): Pair<ByteArray, ByteArray>? {
        val row = repository.row(taskId) ?: return null
        val session = row.session() ?: return null
        return cipherOf(session)
    }

    private fun cipherOf(session: UploadSession): Pair<ByteArray, ByteArray>? {
        val meta = session.encryptMetadata ?: return null
        if (session.storagePolicy?.relay == true) return null
        return Base64.decode(meta.keyPlainText) to Base64.decode(meta.iv)
    }

    private fun buildRequest(row: TransferTask, session: UploadSession, chunk: TransferChunk, token: String?): ChunkRequest {
        val index = chunk.idx.toInt()
        val type = row.policy_type.orEmpty()
        val start = if (row.chunk_size == 0L) 0L else index * row.chunk_size
        val chunkLength = chunkLength(row.size, row.chunk_size, index)
        val skip = chunk.bytes_done.coerceIn(0, chunkLength)
        val range = ChunkBody.FileRange(start + skip, chunkLength - skip, cipherOf(session) != null)
        val urls = session.uploadUrls.orEmpty()
        fun url(i: Int) = urls.getOrNull(i) ?: throw IllegalStateException("Upload session has no upload url #$i")
        val octet = mapOf(HEADER_CONTENT_TYPE to OCTET_STREAM)
        return when (type) {
            StoragePolicy.TYPE_LOCAL -> {
                val client = client(row)
                ChunkRequest(
                    taskId = row.id, index = index, method = "POST",
                    url = UploadApi(client).localChunkUrl(session.sessionId, index),
                    headers = octet + buildMap {
                        put(ApiClient.HEADER_CLIENT_ID, client.clientId)
                        put(ApiClient.HEADER_CORRELATION_ID, Uuid.random().toString())
                        if (token != null) put(HEADER_AUTHORIZATION, "Bearer $token")
                    },
                    body = range, captureEtag = false,
                )
            }
            StoragePolicy.TYPE_REMOTE -> {
                val base = url(0)
                ChunkRequest(
                    taskId = row.id, index = index, method = "POST",
                    url = base + (if ('?' in base) "&" else "?") + "chunk=$index",
                    headers = octet + (HEADER_AUTHORIZATION to session.credential.orEmpty()),
                    body = range, captureEtag = false,
                )
            }
            StoragePolicy.TYPE_ONEDRIVE -> ChunkRequest(
                taskId = row.id, index = index, method = "PUT", url = url(0),
                headers = octet + ("Content-Range" to "bytes ${range.offset}-${start + chunkLength - 1}/${row.size}"),
                body = range, captureEtag = false,
            )
            StoragePolicy.TYPE_S3, StoragePolicy.TYPE_COS, StoragePolicy.TYPE_KS3, StoragePolicy.TYPE_OBS -> ChunkRequest(
                taskId = row.id, index = index, method = "PUT", url = url(index),
                headers = octet, body = range, captureEtag = true,
            )
            StoragePolicy.TYPE_OSS -> ChunkRequest(
                taskId = row.id, index = index, method = "PUT", url = url(index),
                headers = octet, body = range, captureEtag = false,
            )
            StoragePolicy.TYPE_QINIU -> ChunkRequest(
                taskId = row.id, index = index, method = "PUT", url = "${url(0)}/${index + 1}",
                headers = octet + (HEADER_AUTHORIZATION to "UpToken ${session.credential.orEmpty()}"),
                body = range, captureEtag = false,
            )
            StoragePolicy.TYPE_UPYUN -> {
                val boundary = "OpenCloudreveBoundary" + row.id.replace("-", "")
                val fields = buildList {
                    add(FormField("policy", session.uploadPolicy.orEmpty()))
                    add(FormField("authorization", session.credential.orEmpty()))
                    session.mimeType?.takeIf { it.isNotEmpty() }?.let { add(FormField("content-type", it)) }
                }
                ChunkRequest(
                    taskId = row.id, index = index, method = "POST", url = url(0),
                    headers = mapOf(HEADER_CONTENT_TYPE to "multipart/form-data; boundary=$boundary"),
                    body = ChunkBody.Multipart(
                        boundary = boundary,
                        fields = fields,
                        fileField = "file",
                        fileName = row.display_name,
                        fileContentType = row.mime_type?.takeIf { it.isNotEmpty() } ?: OCTET_STREAM,
                        file = range,
                    ),
                    captureEtag = false,
                )
            }
            else -> throw IllegalStateException("Unsupported storage policy: $type")
        }
    }

    private sealed class Verdict {
        data class Done(val etag: String?) : Verdict()
        data class Retry(val code: Int, val message: String, val delayMillis: Long? = null) : Verdict()
        data class Fail(val code: Int, val message: String) : Verdict()
        data object Restart : Verdict()
        data object Realign : Verdict()
    }

    private fun classify(row: TransferTask, session: UploadSession, response: ChunkResponse): Verdict {
        val status = response.statusCode
        val body = response.body.orEmpty()
        return when (val type = row.policy_type.orEmpty()) {
            StoragePolicy.TYPE_LOCAL, StoragePolicy.TYPE_REMOTE -> {
                val env = runCatching { CloudreveJson.decodeFromString(ApiEnvelope.serializer(), body) }.getOrNull()
                when {
                    env == null && (status >= 500 || status == 0) -> Verdict.Retry(ErrorCode.BAD_RESPONSE, "HTTP $status")
                    env == null -> Verdict.Fail(ErrorCode.BAD_RESPONSE, "Unexpected response (HTTP $status)")
                    env.code == 0 -> Verdict.Done(null)
                    env.code == ErrorCode.UPLOAD_SESSION_EXPIRED -> Verdict.Restart
                    env.code == ErrorCode.LOGIN_REQUIRED || env.code == -1 || env.code >= 50000 ->
                        Verdict.Retry(env.code, env.msg)
                    else -> Verdict.Fail(env.code, env.msg)
                }
            }
            StoragePolicy.TYPE_ONEDRIVE -> {
                if (status in 200..299) return Verdict.Done(null)
                val err = runCatching { CloudreveJson.decodeFromString(OneDriveErrorResponse.serializer(), body) }
                    .getOrNull()?.error
                when {
                    err?.innererror?.code == "fragmentOverlap" -> Verdict.Realign
                    err?.retryAfterSeconds != null ->
                        Verdict.Retry(TransferErrorCode.STORAGE_REJECTED, err.message ?: "HTTP $status", err.retryAfterSeconds * 1000)
                    else -> thirdPartyFailure(session, status, err?.message ?: body)
                }
            }
            StoragePolicy.TYPE_QINIU -> {
                if (status !in 200..299) return thirdPartyFailure(session, status, body)
                val etag = runCatching { CloudreveJson.decodeFromString(QiniuChunkResponse.serializer(), body) }
                    .getOrNull()?.etag
                if (etag.isNullOrEmpty()) Verdict.Fail(TransferErrorCode.MISSING_ETAG, "Qiniu response has no etag")
                else Verdict.Done(etag)
            }
            StoragePolicy.TYPE_S3, StoragePolicy.TYPE_COS, StoragePolicy.TYPE_KS3, StoragePolicy.TYPE_OBS -> {
                if (status !in 200..299) return thirdPartyFailure(session, status, body)
                if (response.etag.isNullOrEmpty()) Verdict.Fail(TransferErrorCode.MISSING_ETAG, "$type response has no ETag header")
                else Verdict.Done(response.etag)
            }
            else -> {
                // oss / upyun：2xx 即成功
                if (status in 200..299) Verdict.Done(response.etag) else thirdPartyFailure(session, status, body)
            }
        }
    }

    private fun thirdPartyFailure(session: UploadSession, status: Int, message: String): Verdict {
        val text = "HTTP $status: ${message.take(300)}"
        return when {
            status >= 500 || status == 408 || status == 429 || status == 0 ->
                Verdict.Retry(TransferErrorCode.STORAGE_REJECTED, text)
            sessionExpired(session) -> Verdict.Restart
            else -> Verdict.Fail(TransferErrorCode.STORAGE_REJECTED, text)
        }
    }

    private suspend fun markChunkDone(row: TransferTask, chunk: TransferChunk, etag: String?): TransferOutcome {
        val index = chunk.idx.toInt()
        val length = chunkLength(row.size, row.chunk_size, index)
        repository.updateChunk(row.id, index, ChunkState.DONE, etag, length, 0)
        return progressOutcome(row)
    }

    private suspend fun progressOutcome(row: TransferTask): TransferOutcome {
        val chunks = repository.chunks(row.id)
        val done = chunks.sumOf { c ->
            if (c.state == ChunkState.DONE) chunkLength(row.size, row.chunk_size, c.idx.toInt()) else c.bytes_done
        }
        repository.setBytesDone(row.id, done)
        return TransferOutcome.Continue(chunks.all { it.state == ChunkState.DONE })
    }

    private suspend fun retryChunk(
        row: TransferTask,
        session: UploadSession,
        chunk: TransferChunk,
        code: Int,
        message: String,
        overrideDelay: Long?,
    ): TransferOutcome {
        val attempts = chunk.attempts.toInt() + 1
        val wait = retryPolicy.delayFor(attempts)
            ?: return failTask(row, session, code, message)
        repository.updateChunk(row.id, chunk.idx.toInt(), ChunkState.PENDING, chunk.etag, chunk.bytes_done, attempts)
        return TransferOutcome.RetryAfter(overrideDelay ?: wait)
    }

    private suspend fun failTask(row: TransferTask, session: UploadSession, code: Int, message: String): TransferOutcome {
        if (!isResumable(row.policy_type, session.expires)) {
            // 不可续传的策略失败后会话无意义：删除服务端占位并清空本地会话（与 Web `setError` 一致）
            bestEffortDelete(UploadApi(client(row)), session)
            repository.clearSession(row.id)
        } else {
            repository.releaseRunningChunks(row.id)
        }
        repository.setState(row.id, TransferState.FAILED, code, message)
        return TransferOutcome.Failed(code, message)
    }

    private suspend fun restartTask(row: TransferTask, session: UploadSession): TransferOutcome {
        val attempts = row.attempts.toInt() + 1
        if (retryPolicy.delayFor(attempts) == null) {
            return failTask(row, session, ErrorCode.UPLOAD_SESSION_EXPIRED, "Upload session expired repeatedly")
        }
        repository.clearSession(row.id)
        repository.setAttempts(row.id, attempts)
        repository.setState(row.id, TransferState.QUEUED)
        return TransferOutcome.Restart
    }

    /** OneDrive `fragmentOverlap`：`GET uploadUrl` 取 `nextExpectedRanges[0]` 起点，按该偏移重新划分已确认的分片 */
    private suspend fun realignOneDrive(row: TransferTask, session: UploadSession, chunk: TransferChunk): TransferOutcome {
        val url = session.uploadUrls?.firstOrNull()
            ?: return failTask(row, session, TransferErrorCode.STORAGE_REJECTED, "Missing OneDrive upload url")
        val status = try {
            val (code, body) = rawRequest(client(row), HttpMethod.Get, url, emptyMap(), null, null)
            if (code !in 200..299) null
            else runCatching { CloudreveJson.decodeFromString(OneDriveRangeStatus.serializer(), body) }.getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return retryChunk(row, session, chunk, TransferErrorCode.STORAGE_REJECTED, "fragmentOverlap: range query failed", null)
        val expected = status.nextExpectedRanges.firstOrNull()?.substringBefore('-')?.trim()?.toLongOrNull() ?: row.size
        repository.chunks(row.id).forEach { c ->
            val i = c.idx.toInt()
            val start = if (row.chunk_size == 0L) 0L else i * row.chunk_size
            val length = chunkLength(row.size, row.chunk_size, i)
            when {
                start + length <= expected -> repository.updateChunk(row.id, i, ChunkState.DONE, c.etag, length, 0)
                start >= expected -> repository.updateChunk(row.id, i, ChunkState.PENDING, null, 0, c.attempts.toInt())
                else -> repository.updateChunk(row.id, i, ChunkState.PENDING, null, expected - start, c.attempts.toInt())
            }
        }
        return progressOutcome(row)
    }

    private suspend fun completeMultipart(client: ApiClient, session: UploadSession, body: String, headers: Map<String, String>) {
        val url = session.completeUrl ?: throw StorageRejectedException("Upload session has no completeURL")
        val (status, text) = retryPolicy.retryTransport {
            rawRequest(client, HttpMethod.Post, url, headers, body, ContentType.Application.OctetStream)
        }
        // S3 的 CompleteMultipartUpload 可能以 200 返回 <Error>
        if (status != 200 || text.contains("<Error>")) throw StorageRejectedException("Complete upload failed: HTTP $status ${text.take(300)}")
    }

    private suspend fun completeQiniu(client: ApiClient, session: UploadSession, chunks: List<TransferChunk>) {
        val url = session.uploadUrls?.firstOrNull() ?: throw StorageRejectedException("Missing Qiniu upload url")
        val payload = QiniuFinishRequest(
            mimeType = session.mimeType,
            parts = chunks.sortedBy { it.idx }.map { QiniuPart(it.etag.orEmpty(), it.idx.toInt() + 1) },
        )
        val (status, text) = retryPolicy.retryTransport {
            rawRequest(
                client, HttpMethod.Post, url,
                mapOf(HEADER_AUTHORIZATION to "UpToken ${session.credential.orEmpty()}"),
                CloudreveJson.encodeToString(QiniuFinishRequest.serializer(), payload),
                ContentType.Application.Json,
            )
        }
        if (status !in 200..299) throw StorageRejectedException("Qiniu complete failed: HTTP $status ${text.take(300)}")
    }

    /** 直接请求存储方（不解析 Cloudreve 信封） */
    private suspend fun rawRequest(
        client: ApiClient,
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: ContentType?,
    ): Pair<Int, String> {
        val response = try {
            client.http.request(url) {
                this.method = method
                headers.forEach { (k, v) -> header(k, v) }
                if (body != null) setBody(TextContent(body, contentType ?: ContentType.Application.OctetStream))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "network error", cause = e)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "read error", cause = e)
        }
        return response.status.value to text
    }

    private suspend fun discardSession(row: TransferTask, session: UploadSession) {
        bestEffortDelete(UploadApi(client(row)), session)
        repository.clearSession(row.id)
    }

    private suspend fun bestEffortDelete(api: UploadApi, session: UploadSession) {
        try {
            api.deleteSession(session.sessionId, session.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 会话可能已过期或网络不可用；服务端哨兵任务会清理占位
        }
    }

    private suspend fun mainSiteToken(row: TransferTask, type: String): String? {
        if (type != StoragePolicy.TYPE_LOCAL) return null
        return client(row).accessToken()
            ?: fail(row.id, ErrorCode.LOGIN_REQUIRED, "Not signed in")
    }

    private fun client(row: TransferTask): ApiClient = clients.client(row.account_id)

    private fun sessionExpired(session: UploadSession): Boolean = session.expires <= clock().epochSeconds

    private suspend fun requireUpload(taskId: String): TransferTask {
        val row = repository.row(taskId) ?: throw IllegalArgumentException("Transfer task not found: $taskId")
        require(row.kind == TransferKind.UPLOAD.wire) { "Not an upload task: $taskId" }
        return row
    }

    private suspend fun fail(taskId: String, code: Int, message: String, cause: Throwable? = null): Nothing {
        repository.setState(taskId, TransferState.FAILED, code, message)
        throw TransferFailedException(taskId, code, message, cause)
    }

    private suspend fun <T> withTaskLock(taskId: String, block: suspend () -> T): T {
        val lock = locksGuard.withLock { locks.getOrPut(taskId) { Mutex() } }
        return lock.withLock { block() }
    }

    private fun TransferTask.session(): UploadSession? =
        session_json?.let { runCatching { CloudreveJson.decodeFromString(UploadSession.serializer(), it) }.getOrNull() }

    /** 存储方拒绝完成请求 */
    private class StorageRejectedException(message: String) : Exception(message)

    companion object {
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val OCTET_STREAM = "application/octet-stream"

        /** 可断点续传的策略（与 Web `resumePolicy` 一致） */
        val RESUMABLE_POLICIES = setOf(
            StoragePolicy.TYPE_LOCAL, StoragePolicy.TYPE_REMOTE, StoragePolicy.TYPE_QINIU, StoragePolicy.TYPE_OSS,
            StoragePolicy.TYPE_ONEDRIVE, StoragePolicy.TYPE_S3, StoragePolicy.TYPE_KS3,
        )

        val SUPPORTED_POLICIES = setOf(
            StoragePolicy.TYPE_LOCAL, StoragePolicy.TYPE_REMOTE, StoragePolicy.TYPE_ONEDRIVE, StoragePolicy.TYPE_S3,
            StoragePolicy.TYPE_COS, StoragePolicy.TYPE_KS3, StoragePolicy.TYPE_OBS, StoragePolicy.TYPE_OSS,
            StoragePolicy.TYPE_QINIU, StoragePolicy.TYPE_UPYUN,
        )

        /** 实际上传分支：`relay = true` 一律按本机中转 */
        fun effectivePolicyType(session: UploadSession): String {
            val policy = session.storagePolicy ?: return StoragePolicy.TYPE_LOCAL
            return if (policy.relay) StoragePolicy.TYPE_LOCAL else policy.type
        }

        /** `chunk_size == 0` 或空文件为 1 片，否则 `ceil(size / chunk_size)` */
        fun chunkCount(size: Long, chunkSize: Long): Int =
            if (chunkSize <= 0 || size <= 0) 1 else ((size + chunkSize - 1) / chunkSize).toInt()

        /** 第 [index] 片长度（最后一片为余数） */
        fun chunkLength(size: Long, chunkSize: Long, index: Int): Long {
            if (chunkSize <= 0) return size
            val start = index * chunkSize
            return (size - start).coerceIn(0, chunkSize)
        }

        /** OneDrive 以外的并发数 = `chunk_concurrency ?: 1`；OneDrive 恒为 1 */
        internal fun concurrency(type: String, session: UploadSession): Int =
            if (type == StoragePolicy.TYPE_ONEDRIVE) 1 else (session.storagePolicy?.chunkConcurrency ?: 1).coerceAtLeast(1)

        /** S3 系完成请求 XML：按 `PartNumber = index + 1` 排序，ETag 原样（含引号） */
        internal fun partsXml(chunks: List<TransferChunk>): String = buildString {
            append("<CompleteMultipartUpload>")
            chunks.sortedBy { it.idx }.forEach {
                append("<Part><PartNumber>").append(it.idx + 1).append("</PartNumber><ETag>")
                append(xmlEscape(it.etag.orEmpty())).append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
