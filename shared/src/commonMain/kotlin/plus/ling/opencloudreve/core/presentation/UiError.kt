package plus.ling.opencloudreve.core.presentation

import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.LockConflictException
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.api.TransportException

/** 界面层错误分类；文案由平台按 [ErrorKind] 与 [UiError.code] 本地化 */
enum class ErrorKind {
    /** 无法连接/超时 */
    Network,

    /** 响应不是 Cloudreve 信封（地址错误、反代页面） */
    BadResponse,

    /** 需要登录（401） */
    Unauthorized,

    /** 会话失效（刷新失败），需要重新登录 */
    SessionExpired,
    NotFound,
    Permission,

    /** 对象已存在等冲突（40004、409） */
    Conflict,

    /** 锁冲突（40073），可强制解锁后重试 */
    LockConflict,

    /** 文件版本过期（40076） */
    StaleVersion,

    /** 参数/文件名/类型不合法 */
    Validation,
    Captcha,
    TwoFactor,

    /** 密码错误（分享密码 40069、账户密码 40080） */
    IncorrectPassword,

    /** 容量不足（40051） */
    QuotaExceeded,

    /** 文件过大（40049） */
    FileTooLarge,

    /** 批量操作部分失败（40081） */
    PartialFailure,

    /** 站点未启用该功能（40019） */
    FeatureDisabled,

    /** 账户被封禁或未激活 */
    AccountRestricted,

    /** 链接/签名过期 */
    LinkExpired,
    Unknown,
}

/**
 * 界面层错误。
 * @property code 服务端错误码或客户端自定义码（[ErrorCode.TRANSPORT] 等），无则为 null
 * @property serverMsg 服务端原始消息（仅作补充展示/日志）
 */
data class UiError(
    val kind: ErrorKind,
    val code: Int? = null,
    val serverMsg: String? = null,
)

/** 把共享层异常映射为 [UiError] */
fun Throwable.toUiError(): UiError = when (this) {
    is LockConflictException -> UiError(ErrorKind.LockConflict, code, serverMsg)
    is ApiException -> UiError(kindOf(code), code, serverMsg.ifBlank { null })
    is TransportException ->
        UiError(if (httpStatus != null) ErrorKind.BadResponse else ErrorKind.Network, code, message)
    is SessionExpiredException -> UiError(ErrorKind.SessionExpired)
    else -> UiError(ErrorKind.Unknown, null, message)
}

private fun kindOf(code: Int): ErrorKind = when (code) {
    ErrorCode.LOGIN_REQUIRED -> ErrorKind.Unauthorized
    ErrorCode.CREDENTIAL_INVALID -> ErrorKind.SessionExpired
    ErrorCode.NO_PERMISSION, ErrorCode.GROUP_NOT_ALLOWED, ErrorCode.ADMIN_REQUIRED,
    ErrorCode.OWNER_ONLY, ErrorCode.INSUFFICIENT_SCOPE, ErrorCode.ANONYMOUS_ACCESS_DENIED,
    ErrorCode.DISABLED_SHARE_PREVIEW,
    -> ErrorKind.Permission
    ErrorCode.NOT_FOUND, ErrorCode.FILE_NOT_FOUND, ErrorCode.PARENT_NOT_EXIST, ErrorCode.FILE_DELETED,
    -> ErrorKind.NotFound
    ErrorCode.CONFLICT, ErrorCode.OBJECT_EXIST, ErrorCode.EMAIL_EXISTED -> ErrorKind.Conflict
    ErrorCode.LOCK_CONFLICT -> ErrorKind.LockConflict
    ErrorCode.STALE_VERSION -> ErrorKind.StaleVersion
    ErrorCode.PARAM, ErrorCode.ILLEGAL_OBJECT_NAME, ErrorCode.FILE_TYPE_NOT_ALLOWED, ErrorCode.ROOT_PROTECTED,
    ErrorCode.BATCH_SOURCE_SIZE, ErrorCode.TOO_MANY_URIS, ErrorCode.SAVE_OWN_SHARE,
    -> ErrorKind.Validation
    ErrorCode.CAPTCHA -> ErrorKind.Captcha
    ErrorCode.TWO_FA_CODE -> ErrorKind.TwoFactor
    ErrorCode.INCORRECT_PASSWORD, ErrorCode.INVALID_PASSWORD -> ErrorKind.IncorrectPassword
    ErrorCode.INSUFFICIENT_CAPACITY -> ErrorKind.QuotaExceeded
    ErrorCode.FILE_TOO_LARGE -> ErrorKind.FileTooLarge
    ErrorCode.BATCH_PARTIAL -> ErrorKind.PartialFailure
    ErrorCode.FEATURE_NOT_ENABLED -> ErrorKind.FeatureDisabled
    ErrorCode.USER_BANNED, ErrorCode.USER_NOT_ACTIVATED -> ErrorKind.AccountRestricted
    ErrorCode.SIGN_EXPIRED, ErrorCode.TEMP_LINK_EXPIRED, ErrorCode.UPLOAD_SESSION_EXPIRED -> ErrorKind.LinkExpired
    else -> ErrorKind.Unknown
}
