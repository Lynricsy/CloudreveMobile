package plus.ling.opencloudreve.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 服务端错误码（只列客户端需要区分的） */
object ErrorCode {
    const val NOT_FULLY_SUCCESS = 203
    const val LOGIN_REQUIRED = 401
    const val NO_PERMISSION = 403
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val PARAM = 40001
    const val OBJECT_EXIST = 40004
    const val SIGN_EXPIRED = 40005
    const val GROUP_NOT_ALLOWED = 40007
    const val ADMIN_REQUIRED = 40008
    const val UPLOAD_SESSION_EXPIRED = 40011
    const val BATCH_SOURCE_SIZE = 40014
    const val PARENT_NOT_EXIST = 40016
    const val USER_BANNED = 40017
    const val USER_NOT_ACTIVATED = 40018
    const val FEATURE_NOT_ENABLED = 40019
    const val CREDENTIAL_INVALID = 40020
    const val TWO_FA_CODE = 40022
    const val WEBAUTHN_FAILED = 40025
    const val CAPTCHA = 40026
    const val TEMP_LINK_EXPIRED = 40030
    const val EMAIL_EXISTED = 40032
    const val FILE_NOT_FOUND = 40044
    const val FILE_TOO_LARGE = 40049
    const val FILE_TYPE_NOT_ALLOWED = 40050
    const val INSUFFICIENT_CAPACITY = 40051
    const val ILLEGAL_OBJECT_NAME = 40052
    const val ROOT_PROTECTED = 40053
    const val SAVE_OWN_SHARE = 40059
    const val INCORRECT_PASSWORD = 40069
    const val DISABLED_SHARE_PREVIEW = 40070
    const val LOCK_CONFLICT = 40073
    const val TOO_MANY_URIS = 40074
    const val STALE_VERSION = 40076
    const val FILE_DELETED = 40078
    const val INVALID_PASSWORD = 40080
    const val BATCH_PARTIAL = 40081
    const val OWNER_ONLY = 40082
    const val ANONYMOUS_ACCESS_DENIED = 40088
    const val INSUFFICIENT_SCOPE = 40089

    /** 客户端自定义：网络/传输失败 */
    const val TRANSPORT = -100

    /** 客户端自定义：响应不是 Cloudreve 信封（地址错误、反代页面等） */
    const val BAD_RESPONSE = -101
}

/** 共享层所有可预期错误的基类。Swift 侧通过 `@Throws(Exception::class)` 捕获 */
open class CloudreveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 服务端返回了非 0 的 code */
open class ApiException(
    val code: Int,
    val serverMsg: String,
    val data: JsonElement? = null,
    val aggregated: Map<String, ApiEnvelope>? = null,
    val correlationId: String? = null,
) : CloudreveException("[$code] $serverMsg")

/** 锁冲突 40073，[conflicts] 来自 `data` */
class LockConflictException(
    serverMsg: String,
    data: JsonElement?,
    val conflicts: List<LockConflict>,
) : ApiException(ErrorCode.LOCK_CONFLICT, serverMsg, data)

@Serializable
data class LockConflict(
    val path: String? = null,
    val token: String? = null,
    val type: Int = 0,
    val owner: LockOwner? = null,
)

@Serializable
data class LockOwner(val application: LockApplication? = null)

@Serializable
data class LockApplication(val type: String? = null, @SerialName("inner_xml") val innerXml: String? = null, @SerialName("viewer_id") val viewerId: String? = null)

/** 网络或协议层错误（无法连接、超时、非信封响应） */
class TransportException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : CloudreveException(message, cause) {
    val code: Int get() = if (httpStatus != null) ErrorCode.BAD_RESPONSE else ErrorCode.TRANSPORT
}

/** 会话已失效（刷新失败），账户已被登出 */
class SessionExpiredException(val accountId: String) : CloudreveException("Session expired")
