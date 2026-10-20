package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClient.Companion.appendQuery
import plus.ling.opencloudreve.core.api.ApiEnvelope
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.model.ArchiveListResponse
import plus.ling.opencloudreve.core.model.CreateFileRequest
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.DeleteFileRequest
import plus.ling.opencloudreve.core.model.ExplorerView
import plus.ling.opencloudreve.core.model.FileHashRequest
import plus.ling.opencloudreve.core.model.FileHashResponse
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileUrlRequest
import plus.ling.opencloudreve.core.model.FileUrlResponse
import plus.ling.opencloudreve.core.model.FileVersionRequest
import plus.ling.opencloudreve.core.model.FullTextSearchResponse
import plus.ling.opencloudreve.core.model.HashSearchRequest
import plus.ling.opencloudreve.core.model.HashSearchResponse
import plus.ling.opencloudreve.core.model.ListPage
import plus.ling.opencloudreve.core.model.MetadataPatch
import plus.ling.opencloudreve.core.model.MountPolicyRequest
import plus.ling.opencloudreve.core.model.MoveFileRequest
import plus.ling.opencloudreve.core.model.PatchMetadataRequest
import plus.ling.opencloudreve.core.model.PatchViewRequest
import plus.ling.opencloudreve.core.model.PinFileRequest
import plus.ling.opencloudreve.core.model.RenameFileRequest
import plus.ling.opencloudreve.core.model.SourceLink
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.ThumbResponse
import plus.ling.opencloudreve.core.model.UnlockFileRequest
import plus.ling.opencloudreve.core.model.UrisRequest
import plus.ling.opencloudreve.core.model.ViewerSessionRequest
import plus.ling.opencloudreve.core.model.ViewerSessionResponse

/**
 * 文件接口（`/file/…`，不含上传会话与分片，见 `core.transfer.UploadApi`）。
 * 所有 uri 参数都是 `cloudreve://` URI 字符串。
 */
class FileApi(private val client: ApiClient) {

    /**
     * `GET /file` 列目录/搜索。游标模式传 [nextPageToken]（取自上一页 `pagination.next_token`）；
     * 页码模式（响应带 `total_items`）传 [page]（从 0 起）。[pageSize] 为 null 时用目录视图设置。
     *
     * 站点开启 `use_sse_for_search` 时搜索响应是 SSE 流（`file` 批次 + 末尾 `list`），这里透明地合并为一页。
     */
    @Throws(Exception::class)
    suspend fun list(
        uri: String,
        page: Int? = null,
        pageSize: Int? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
        nextPageToken: String? = null,
        hash: Boolean = false,
    ): ListPage {
        val query = mapOf(
            "uri" to uri,
            "page" to page,
            "page_size" to pageSize,
            "order_by" to orderBy,
            "order_direction" to orderDirection,
            "next_page_token" to nextPageToken,
            "hash" to if (hash) true else null,
        )
        if (CrUri.parseOrNull(uri)?.isSearch() == true) {
            return listSearch(query)
        }
        return client.get("/file", query)
    }

    /** 搜索可能被服务端切换为 SSE；先自行请求，非 SSE 时按信封处理，401 时交给常规路径刷新重放 */
    private suspend fun listSearch(query: Map<String, Any?>): ListPage {
        val token = client.accessToken()
        val response = try {
            client.http.request(client.apiBase + "/file") {
                method = HttpMethod.Get
                with(client) { applyClientHeaders(token) }
                appendQuery(query)
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
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        if (contentType.startsWith("text/event-stream")) {
            return parseSseList(text)
        }
        val env = try {
            CloudreveJson.decodeFromString(ApiEnvelope.serializer(), text)
        } catch (e: Exception) {
            throw TransportException("Unexpected response (HTTP ${response.status.value})", response.status.value, e)
        }
        if (env.code == ErrorCode.LOGIN_REQUIRED && token != null) {
            return client.get("/file", query)
        }
        ApiClient.throwIfError(env)
        return ApiClient.decodeData(env.data, ListPage.serializer())
    }

    /** `GET /file/info`：[uri] 与 [id] 二选一（仅 uri 为空时用 id） */
    @Throws(Exception::class)
    suspend fun info(
        uri: String? = null,
        id: String? = null,
        extended: Boolean = false,
        folderSummary: Boolean = false,
    ): FileItem = client.get(
        "/file/info",
        mapOf(
            "uri" to uri,
            "id" to id,
            "extended" to if (extended) true else null,
            "folder_summary" to if (folderSummary) true else null,
        ),
    )

    /** `POST /file/create`；[type] 为 `file`/`folder`；[errOnConflict] 为 true 时重名返回 40004 */
    @Throws(Exception::class)
    suspend fun create(
        uri: String,
        type: String,
        metadata: Map<String, String>? = null,
        errOnConflict: Boolean = false,
    ): FileItem = client.post("/file/create", CreateFileRequest(uri, type, metadata, errOnConflict))

    /** `POST /file/rename` */
    @Throws(Exception::class)
    suspend fun rename(uri: String, newName: String): FileItem =
        client.post("/file/rename", RenameFileRequest(uri, newName))

    /** `POST /file/move`；[copy] 为 true 时复制（也用于保存分享到我的文件） */
    @Throws(Exception::class)
    suspend fun move(uris: List<String>, dst: String, copy: Boolean = false) {
        client.post<Unit, MoveFileRequest>("/file/move", MoveFileRequest(uris, dst, copy))
    }

    /** `DELETE /file`：默认进回收站；[skipSoftDelete] 彻底删除；[unlink] 仅解除链接（需 advance_delete） */
    @Throws(Exception::class)
    suspend fun delete(uris: List<String>, unlink: Boolean = false, skipSoftDelete: Boolean = false) {
        client.delete<Unit, DeleteFileRequest>("/file", DeleteFileRequest(uris, unlink, skipSoftDelete))
    }

    /** `POST /file/restore`：从回收站还原 */
    @Throws(Exception::class)
    suspend fun restore(uris: List<String>) {
        client.post<Unit, UrisRequest>("/file/restore", UrisRequest(uris))
    }

    /** `DELETE /file/trash`：清空回收站 */
    @Throws(Exception::class)
    suspend fun emptyTrash() {
        client.deleteNoBody<Unit>("/file/trash")
    }

    /** `DELETE /file/lock`：用锁冲突中的 token 强制解锁 */
    @Throws(Exception::class)
    suspend fun unlock(tokens: List<String>) {
        client.delete<Unit, UnlockFileRequest>("/file/lock", UnlockFileRequest(tokens))
    }

    /** `PATCH /file/metadata` */
    @Throws(Exception::class)
    suspend fun patchMetadata(uris: List<String>, patches: List<MetadataPatch>) {
        client.patch<Unit, PatchMetadataRequest>("/file/metadata", PatchMetadataRequest(uris, patches))
    }

    /** `PUT /file/pin`：固定到侧栏 */
    @Throws(Exception::class)
    suspend fun pin(uri: String, name: String? = null) {
        client.put<Unit, PinFileRequest>("/file/pin", PinFileRequest(uri, name))
    }

    /** `DELETE /file/pin`：取消固定（用户组默认固定项返回 403） */
    @Throws(Exception::class)
    suspend fun unpin(uri: String, name: String? = null) {
        client.delete<Unit, PinFileRequest>("/file/pin", PinFileRequest(uri, name))
    }

    /** `PATCH /file/view`：同步目录视图设置；[view] 为 null 表示删除 */
    @Throws(Exception::class)
    suspend fun patchView(uri: String, view: ExplorerView?) {
        client.patch<Unit, PatchViewRequest>("/file/view", PatchViewRequest(uri, view))
    }

    /** `PATCH /file/policy`：为目录挂载存储策略；[policyId] 为空串恢复继承。返回实际生效策略 */
    @Throws(Exception::class)
    suspend fun mountPolicy(uri: String, policyId: String): StoragePolicy =
        client.patch("/file/policy", MountPolicyRequest(uri, policyId))

    /** `POST /file/version/current`：设为当前版本 */
    @Throws(Exception::class)
    suspend fun setVersion(uri: String, version: String) {
        client.post<Unit, FileVersionRequest>("/file/version/current", FileVersionRequest(uri, version))
    }

    /** `DELETE /file/version` */
    @Throws(Exception::class)
    suspend fun deleteVersion(uri: String, version: String) {
        client.delete<Unit, FileVersionRequest>("/file/version", FileVersionRequest(uri, version))
    }

    /**
     * `PUT /file/source`：创建/获取永久直链。部分失败（40081）时仍返回已成功的项，
     * 失败项放在 [SourceLinksResult.failures]（uri → 错误信封）。其它错误照常抛出。
     */
    @Throws(Exception::class)
    suspend fun source(uris: List<String>): SourceLinksResult {
        val env = client.envelope(HttpMethod.Put, "/file/source", body = client.encodeBody(UrisRequest(uris)))
        if (env.code != 0 && env.code != ErrorCode.BATCH_PARTIAL) ApiClient.throwIfError(env)
        val links = env.data?.let {
            runCatching { CloudreveJson.decodeFromJsonElement(ListSerializer(SourceLink.serializer()), it) }.getOrNull()
        } ?: emptyList()
        return SourceLinksResult(links, env.aggregatedError ?: emptyMap())
    }

    /** `DELETE /file/source/:id`（[id] 为 `DirectLink.id`） */
    @Throws(Exception::class)
    suspend fun deleteSource(id: String) {
        client.deleteNoBody<Unit>("/file/source/$id")
    }

    /** `POST /file/hash`：查询当前版本 SHA-256；[compute] 为未计算的文件创建任务 */
    @Throws(Exception::class)
    suspend fun hash(uris: List<String>, compute: Boolean = false): FileHashResponse =
        client.post("/file/hash", FileHashRequest(uris, compute))

    /** `POST /file/hash/search`：按 SHA-256 在“我的文件”中查找 */
    @Throws(Exception::class)
    suspend fun hashSearch(sha256: List<String>): HashSearchResponse =
        client.post("/file/hash/search", HashSearchRequest(sha256))

    /** `GET /file/search` 全文搜索（站点 `full_text_search` 开启时可用，否则 40019） */
    @Throws(Exception::class)
    suspend fun fullTextSearch(query: String, offset: Int = 0): FullTextSearchResponse =
        client.get("/file/search", mapOf("query" to query, "offset" to offset))

    /** `POST /file/url`：获取下载/预览地址；[contextHint] 取自列目录响应 */
    @Throws(Exception::class)
    suspend fun url(request: FileUrlRequest, contextHint: String? = null): FileUrlResponse =
        client.post("/file/url", request, headers = hintHeader(contextHint))

    /** `GET /file/thumb`：缩略图签名地址（可能阻塞到生成完成；不可用时 40077 或 -1） */
    @Throws(Exception::class)
    suspend fun thumb(uri: String, contextHint: String? = null): ThumbResponse =
        client.get("/file/thumb", mapOf("uri" to uri), headers = hintHeader(contextHint))

    /** `GET /file/archive`：列出 zip/7z 内容（需 archive_task 权限） */
    @Throws(Exception::class)
    suspend fun archiveList(uri: String, entity: String? = null, textEncoding: String? = null): ArchiveListResponse =
        client.get("/file/archive", mapOf("uri" to uri, "entity" to entity, "text_encoding" to textEncoding))

    /**
     * `PUT /file/content`：以原始字节覆盖文件内容（在线编辑保存）。
     * [previous] 为编辑开始时的 `primary_entity`，与当前主版本不符时 40076；超过在线编辑上限 40049；锁冲突 40073。
     */
    @Throws(Exception::class)
    suspend fun putContent(uri: String, bytes: ByteArray, previous: String? = null): FileItem =
        client.callBytes(
            HttpMethod.Put,
            "/file/content",
            FileItem.serializer(),
            bytes,
            ContentType.Application.OctetStream.toString(),
            mapOf("uri" to uri, "previous" to previous),
        )

    /** `PUT /file/viewerSession`：WOPI/编辑会话 */
    @Throws(Exception::class)
    suspend fun viewerSession(request: ViewerSessionRequest): ViewerSessionResponse =
        client.put("/file/viewerSession", request)

    private fun hintHeader(hint: String?): Map<String, String> =
        if (hint.isNullOrEmpty()) emptyMap() else mapOf(ApiClient.HEADER_CONTEXT_HINT to hint)

    internal companion object {
        /** 解析 SSE 搜索流：合并所有 `file` 批次，以末尾 `list` 事件为准（其 files 为空时用合并结果） */
        fun parseSseList(text: String): ListPage {
            val streamed = ArrayList<FileItem>()
            var list: ListPage? = null
            var event = ""
            val data = StringBuilder()
            fun dispatch() {
                if (data.isEmpty()) {
                    event = ""
                    return
                }
                val payload = data.toString()
                when (event) {
                    "file" -> streamed += CloudreveJson.decodeFromString(ListSerializer(FileItem.serializer()), payload)
                    "list" -> list = CloudreveJson.decodeFromString(ListPage.serializer(), payload)
                }
                event = ""
                data.clear()
            }
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd('\r')
                when {
                    line.isEmpty() -> dispatch()
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
            dispatch()
            val result = list ?: throw TransportException("Incomplete search stream", 200)
            return if (result.files.isEmpty() && streamed.isNotEmpty()) result.copy(files = streamed) else result
        }
    }
}

/** [FileApi.source] 的结果：成功的直链与失败项 */
data class SourceLinksResult(
    val links: List<SourceLink>,
    val failures: Map<String, ApiEnvelope>,
)
