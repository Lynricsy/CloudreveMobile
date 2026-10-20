package plus.ling.opencloudreve.core.transfer

import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.serializer
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.model.CreateUploadSessionRequest
import plus.ling.opencloudreve.core.model.DeleteUploadSessionRequest
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.UploadSession

/** 上传会话相关的 Cloudreve 主站端点 */
class UploadApi(private val client: ApiClient) {
    /** `PUT /file/upload`：创建上传会话（40004 目标已存在、40051 容量不足、40049/40052 校验失败） */
    @Throws(Exception::class)
    suspend fun createSession(request: CreateUploadSessionRequest): UploadSession =
        client.put<UploadSession, CreateUploadSessionRequest>("/file/upload", request)

    /** `DELETE /file/upload {id, uri}`：取消会话并清理占位文件 */
    @Throws(Exception::class)
    suspend fun deleteSession(sessionId: String, uri: String) {
        client.call(
            HttpMethod.Delete,
            "/file/upload",
            Unit.serializer(),
            body = CloudreveJson.encodeToJsonElement(
                DeleteUploadSessionRequest.serializer(),
                DeleteUploadSessionRequest(sessionId, uri),
            ),
        )
    }

    /**
     * 客户端负责的上传完成回调（只靠路径中的 `callback_secret` 认证，不带 JWT）：
     * s3/cos/ks3 为 `GET /callback/{type}/{id}/{secret}`，onedrive 为 `POST /callback/onedrive/{id}/{secret}`。
     */
    @Throws(Exception::class)
    suspend fun callback(policyType: String, sessionId: String, callbackSecret: String) {
        val method = if (policyType == StoragePolicy.TYPE_ONEDRIVE) HttpMethod.Post else HttpMethod.Get
        client.call(method, callbackPath(policyType, sessionId, callbackSecret), Unit.serializer(), noAuth = true)
    }

    /** 本机（或 relay）分片上传的绝对 URL：`POST /api/v4/file/upload/{session_id}/{index}` */
    fun localChunkUrl(sessionId: String, index: Int): String = "${client.apiBase}/file/upload/$sessionId/$index"

    companion object {
        /** 需要客户端调用回调的策略 */
        val CLIENT_CALLBACK_POLICIES = setOf(
            StoragePolicy.TYPE_S3,
            StoragePolicy.TYPE_COS,
            StoragePolicy.TYPE_KS3,
            StoragePolicy.TYPE_ONEDRIVE,
        )

        fun callbackPath(policyType: String, sessionId: String, callbackSecret: String): String =
            "/callback/$policyType/$sessionId/$callbackSecret"
    }
}
