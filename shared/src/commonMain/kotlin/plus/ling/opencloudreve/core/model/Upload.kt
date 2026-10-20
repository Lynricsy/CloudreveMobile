package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `PUT /file/upload` 请求体（`CreateUploadSessionService`） */
@Serializable
data class CreateUploadSessionRequest(
    /** 目标文件完整 URI（目录 + 文件名） */
    val uri: String,
    val size: Long,
    /** 毫秒时间戳；> 0 时完成后写入文件修改时间 */
    @SerialName("last_modified") val lastModified: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("policy_id") val policyId: String? = null,
    val metadata: Map<String, String>? = null,
    /** 只能为 null、`live_photo`、`version`（覆盖上传） */
    @SerialName("entity_type") val entityType: String? = null,
    /** 客户端支持的加密算法，目前只有 `aes-256-ctr` */
    @SerialName("encryption_supported") val encryptionSupported: List<String>? = null,
    /** 旧版本 id，用于过期版本检测（40076） */
    val previous: String? = null,
) {
    companion object {
        const val ENTITY_TYPE_VERSION = "version"
        const val ENTITY_TYPE_LIVE_PHOTO = "live_photo"
        const val CIPHER_AES_256_CTR = "aes-256-ctr"
    }
}

/** 客户端加密参数；[keyPlainText] 为 32 字节、[iv] 为 16 字节，均为标准 base64 */
@Serializable
data class EncryptMetadata(
    val algorithm: String = CreateUploadSessionRequest.CIPHER_AES_256_CTR,
    @SerialName("key_plain_text") val keyPlainText: String,
    val iv: String,
)

/** `PUT /file/upload` 响应（`UploadSessionResponse`） */
@Serializable
data class UploadSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("upload_id") val uploadId: String? = null,
    /** 0 = 不分片，整个文件一次发送 */
    @SerialName("chunk_size") val chunkSize: Long = 0,
    /** 会话过期时间（Unix 秒） */
    val expires: Long = 0,
    @SerialName("upload_urls") val uploadUrls: List<String>? = null,
    val credential: String? = null,
    @SerialName("completeURL") val completeUrl: String? = null,
    /** 服务端最终解析出的真实策略（不会是负载均衡父策略） */
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
    val uri: String,
    @SerialName("callback_secret") val callbackSecret: String = "",
    @SerialName("mime_type") val mimeType: String? = null,
    /** 又拍云表单上传的 base64 policy */
    @SerialName("upload_policy") val uploadPolicy: String? = null,
    @SerialName("encrypt_metadata") val encryptMetadata: EncryptMetadata? = null,
)

/** `DELETE /file/upload` 请求体 */
@Serializable
data class DeleteUploadSessionRequest(
    /** 会话 id */
    val id: String,
    val uri: String,
)

/** 七牛分片响应 */
@Serializable
data class QiniuChunkResponse(val etag: String? = null, val md5: String? = null)

/** 七牛完成请求 */
@Serializable
data class QiniuFinishRequest(
    val mimeType: String? = null,
    val parts: List<QiniuPart>,
)

@Serializable
data class QiniuPart(val etag: String, val partNumber: Int)

/** OneDrive（Graph）上传会话状态，`GET uploadUrl` 或中间分片的响应 */
@Serializable
data class OneDriveRangeStatus(
    val nextExpectedRanges: List<String> = emptyList(),
    val expirationDateTime: String? = null,
)

/** Graph 错误体 `{error:{code,message,innererror:{code},retryAfterSeconds?}}` */
@Serializable
data class OneDriveErrorResponse(val error: OneDriveError? = null)

@Serializable
data class OneDriveError(
    val code: String? = null,
    val message: String? = null,
    val innererror: OneDriveInnerError? = null,
    val retryAfterSeconds: Long? = null,
)

@Serializable
data class OneDriveInnerError(val code: String? = null)
