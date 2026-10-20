package plus.ling.opencloudreve.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError

/**
 * 按 [UiError.kind] / [UiError.code] 本地化错误文案。
 * 具体错误码优先于分类；[ErrorKind.Validation] 与 [ErrorKind.Unknown] 在有服务端消息时附带原文便于排查。
 */
@Composable
fun UiError.message(): String {
    val base = stringResource(messageRes())
    val detail = serverMsg?.takeIf { it.isNotBlank() }
    return if (detail != null && (kind == ErrorKind.Validation || kind == ErrorKind.Unknown)) {
        stringResource(R.string.error_with_detail, base, detail)
    } else {
        base
    }
}

@StringRes
private fun UiError.messageRes(): Int = when (code) {
    ErrorCode.WEBAUTHN_FAILED -> R.string.error_passkey_fingerprint
    ErrorCode.INVALID_PASSWORD -> R.string.error_incorrect_credentials
    ErrorCode.INCORRECT_PASSWORD -> R.string.error_incorrect_password
    ErrorCode.USER_BANNED -> R.string.error_user_banned
    ErrorCode.USER_NOT_ACTIVATED -> R.string.error_user_not_activated
    ErrorCode.EMAIL_EXISTED -> R.string.error_email_existed
    else -> when (kind) {
        ErrorKind.Network -> R.string.error_network
        ErrorKind.BadResponse -> R.string.error_bad_response
        ErrorKind.Unauthorized -> R.string.error_unauthorized
        ErrorKind.SessionExpired -> R.string.error_session_expired
        ErrorKind.NotFound -> R.string.error_not_found
        ErrorKind.Permission -> R.string.error_permission
        ErrorKind.Conflict -> R.string.error_conflict
        ErrorKind.LockConflict -> R.string.error_lock_conflict
        ErrorKind.StaleVersion -> R.string.error_stale_version
        ErrorKind.Validation -> R.string.error_validation
        ErrorKind.Captcha -> R.string.error_captcha
        ErrorKind.TwoFactor -> R.string.error_two_factor
        ErrorKind.IncorrectPassword -> R.string.error_incorrect_password
        ErrorKind.QuotaExceeded -> R.string.error_quota
        ErrorKind.FileTooLarge -> R.string.error_file_too_large
        ErrorKind.PartialFailure -> R.string.error_partial
        ErrorKind.FeatureDisabled -> R.string.error_feature_disabled
        ErrorKind.AccountRestricted -> R.string.error_user_banned
        ErrorKind.LinkExpired -> R.string.error_link_expired
        ErrorKind.Unknown -> R.string.error_unknown
    }
}
