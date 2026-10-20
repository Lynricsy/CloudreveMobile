package plus.ling.opencloudreve.core.presentation.settings

import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.model.UriCodec
import plus.ling.opencloudreve.core.model.OAuthGrant
import plus.ling.opencloudreve.core.model.Passkey
import plus.ling.opencloudreve.core.model.PatchUserSettingRequest
import plus.ling.opencloudreve.core.model.UserSettings
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/** 开启 2FA 时展示的密钥与二维码内容 */
data class TwoFactorSetup(
    /** TOTP 密钥（base32），供手动输入 */
    val secret: String,
    /** `otpauth://totp/<站点名>:<邮箱>?secret=<密钥>&issuer=<站点名>`，平台渲染为二维码 */
    val otpauthUrl: String,
)

/** 正在执行的安全操作 */
enum class SecurityOperation { ChangePassword, EnableTwoFactor, DisableTwoFactor, RegisterPasskey, DeletePasskey, RevokeGrant }

data class SecurityState(
    val accountId: String,
    val settings: UserSettings? = null,
    val loading: Boolean = false,
    /** 加载失败（操作失败通过 [SecurityEffect.Failed] 通知） */
    val error: UiError? = null,
    val busy: SecurityOperation? = null,
    /** 非 null 表示正在开启 2FA：展示密钥/二维码并等待输入验证码 */
    val twoFactorSetup: TwoFactorSetup? = null,
    /** 是否可在 App 内注册 Passkey（仅 Android 原生 Credential Manager；iOS 提示在网页添加） */
    val passkeyRegistrationSupported: Boolean = false,
    /** 注册 Passkey 进行中：已把创建选项交给平台，等待 [SecurityIntent.FinishPasskeyRegistration] */
    val passkeyRegistrationPending: Boolean = false,
) {
    val twoFactorEnabled: Boolean get() = settings?.twoFaEnabled ?: false

    /** 无密码账户（仅 Passkey/第三方登录）不能修改密码 */
    val canChangePassword: Boolean get() = settings != null && !settings.passwordless
    val passkeys: List<Passkey> get() = settings?.passkeys.orEmpty()
    val oauthGrants: List<OAuthGrant> get() = settings?.oauthGrants.orEmpty()
}

sealed interface SecurityIntent {
    data object Refresh : SecurityIntent

    /** 修改密码（当前 4–128 位，新 6–128 位）；成功后旧 refresh token 失效，本地登出并要求重新登录 */
    data class ChangePassword(val currentPassword: String, val newPassword: String) : SecurityIntent

    /** 取 TOTP 密钥，进入 [SecurityState.twoFactorSetup] */
    data object BeginEnableTwoFactor : SecurityIntent
    data class ConfirmEnableTwoFactor(val code: String) : SecurityIntent
    data object CancelEnableTwoFactor : SecurityIntent
    data class DisableTwoFactor(val code: String) : SecurityIntent

    /** Android：取创建选项，通过 [SecurityEffect.CreatePasskey] 交给 Credential Manager */
    data object BeginPasskeyRegistration : SecurityIntent

    /** [responseJson] 为平台返回的注册凭证 JSON 原文；[name] 可含 `{os}`/`{browser}` 占位符（服务端按 [userAgent] 替换） */
    data class FinishPasskeyRegistration(val responseJson: String, val name: String, val userAgent: String) : SecurityIntent

    /** 用户取消或平台创建失败 */
    data object CancelPasskeyRegistration : SecurityIntent
    data class DeletePasskey(val id: String) : SecurityIntent
    data class RevokeOAuthGrant(val clientId: String) : SecurityIntent
    data object DismissError : SecurityIntent
}

sealed interface SecurityEffect {
    /**
     * 密码已修改，账户已在本地登出（`AuthEvent.SignedOut`）。平台应导航到登录页并预填 [siteUrl]/[email]。
     */
    data class PasswordChanged(val siteUrl: String, val email: String) : SecurityEffect
    data object TwoFactorEnabled : SecurityEffect
    data object TwoFactorDisabled : SecurityEffect

    /** 把 [requestJson]（PublicKeyCredentialCreationOptions）交给 `CreatePublicKeyCredentialRequest` */
    data class CreatePasskey(val requestJson: String) : SecurityEffect
    data class PasskeyAdded(val passkey: Passkey) : SecurityEffect
    data object PasskeyDeleted : SecurityEffect
    data object GrantRevoked : SecurityEffect
    data class Failed(val operation: SecurityOperation?, val error: UiError) : SecurityEffect
}

/**
 * 安全设置：修改密码、两步验证、Passkey、已授权应用。
 */
class SecurityViewModel(
    val accountId: String,
    private val userSettings: UserSettingsRepository,
    private val accounts: AccountRepository,
    private val passkeys: PasskeyFlow,
    private val site: SiteConfigRepository,
    platformName: String,
) : CoreViewModel<SecurityState, SecurityIntent, SecurityEffect>(
    SecurityState(accountId, passkeyRegistrationSupported = platformName == PlatformContext.PLATFORM_ANDROID),
) {

    init {
        load()
    }

    override fun on(intent: SecurityIntent) {
        when (intent) {
            SecurityIntent.Refresh -> load()
            is SecurityIntent.ChangePassword -> changePassword(intent.currentPassword, intent.newPassword)
            SecurityIntent.BeginEnableTwoFactor -> run(SecurityOperation.EnableTwoFactor) {
                val secret = userSettings.init2fa()
                val setup = TwoFactorSetup(secret, otpauthUrl(issuer(), label(), secret))
                updateState { it.copy(busy = null, twoFactorSetup = setup) }
            }
            is SecurityIntent.ConfirmEnableTwoFactor -> {
                if (currentState.twoFactorSetup == null) return
                val code = intent.code.trim()
                if (!isOtp(code)) return validationError(SecurityOperation.EnableTwoFactor)
                run(SecurityOperation.EnableTwoFactor) {
                    userSettings.patchSettings(PatchUserSettingRequest(twoFaEnabled = true, twoFaCode = code))
                    val settings = userSettings.settings()
                    updateState { it.copy(busy = null, twoFactorSetup = null, settings = settings) }
                    sendEffect(SecurityEffect.TwoFactorEnabled)
                }
            }
            SecurityIntent.CancelEnableTwoFactor -> updateState { it.copy(twoFactorSetup = null) }
            is SecurityIntent.DisableTwoFactor -> {
                val code = intent.code.trim()
                if (!isOtp(code)) return validationError(SecurityOperation.DisableTwoFactor)
                run(SecurityOperation.DisableTwoFactor) {
                    userSettings.patchSettings(PatchUserSettingRequest(twoFaEnabled = false, twoFaCode = code))
                    val settings = userSettings.settings()
                    updateState { it.copy(busy = null, settings = settings) }
                    sendEffect(SecurityEffect.TwoFactorDisabled)
                }
            }
            SecurityIntent.BeginPasskeyRegistration -> {
                if (!currentState.passkeyRegistrationSupported) {
                    sendEffect(SecurityEffect.Failed(SecurityOperation.RegisterPasskey, UiError(ErrorKind.FeatureDisabled)))
                    return
                }
                run(SecurityOperation.RegisterPasskey) {
                    val options = passkeys.beginRegister(accountId)
                    updateState { it.copy(busy = null, passkeyRegistrationPending = true) }
                    sendEffect(SecurityEffect.CreatePasskey(options))
                }
            }
            is SecurityIntent.FinishPasskeyRegistration -> {
                if (!currentState.passkeyRegistrationPending) return
                run(SecurityOperation.RegisterPasskey, onError = { updateState { it.copy(passkeyRegistrationPending = false) } }) {
                    val added = passkeys.finishRegister(accountId, intent.responseJson, intent.name, intent.userAgent)
                    val settings = userSettings.settings()
                    updateState { it.copy(busy = null, passkeyRegistrationPending = false, settings = settings) }
                    sendEffect(SecurityEffect.PasskeyAdded(added))
                }
            }
            SecurityIntent.CancelPasskeyRegistration -> updateState { it.copy(passkeyRegistrationPending = false) }
            is SecurityIntent.DeletePasskey -> run(SecurityOperation.DeletePasskey) {
                userSettings.deletePasskey(intent.id)
                updateState { s ->
                    s.copy(busy = null, settings = s.settings?.copy(passkeys = s.settings.passkeys.filterNot { it.id == intent.id }))
                }
                sendEffect(SecurityEffect.PasskeyDeleted)
            }
            is SecurityIntent.RevokeOAuthGrant -> run(SecurityOperation.RevokeGrant) {
                userSettings.revokeOAuthGrant(intent.clientId)
                updateState { s ->
                    s.copy(
                        busy = null,
                        settings = s.settings?.copy(oauthGrants = s.settings.oauthGrants.filterNot { it.clientId == intent.clientId }),
                    )
                }
                sendEffect(SecurityEffect.GrantRevoked)
            }
            SecurityIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val settings = userSettings.settings()
            updateState { it.copy(loading = false, settings = settings) }
        }
    }

    private fun changePassword(current: String, new: String) {
        if (current.length !in 4..128 || new.length !in 6..128) return validationError(SecurityOperation.ChangePassword)
        run(SecurityOperation.ChangePassword) {
            userSettings.patchSettings(PatchUserSettingRequest(currentPassword = current, newPassword = new))
            // 改密码后服务端吊销全部 refresh token：本地登出（发出 AuthEvent.SignedOut），由平台引导重新登录
            val account = accounts.account(accountId)
            val siteUrl = account?.siteUrl.orEmpty()
            val email = account?.email ?: userSettings.user.value?.email.orEmpty()
            accounts.signOut(accountId)
            updateState { it.copy(busy = null) }
            sendEffect(SecurityEffect.PasswordChanged(siteUrl, email))
        }
    }

    private fun run(op: SecurityOperation, onError: () -> Unit = {}, block: suspend () -> Unit) {
        if (currentState.busy != null) return
        updateState { it.copy(busy = op) }
        launchCatching(onError = { e ->
            onError()
            updateState { it.copy(busy = null) }
            sendEffect(SecurityEffect.Failed(op, e))
        }) { block() }
    }

    private fun validationError(op: SecurityOperation) {
        sendEffect(SecurityEffect.Failed(op, UiError(ErrorKind.Validation)))
    }

    private suspend fun issuer(): String =
        runCatching { site.basic(accountId).title }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_ISSUER

    private suspend fun label(): String =
        accounts.account(accountId)?.email?.takeIf { it.isNotEmpty() }
            ?: runCatching { userSettings.me() }.getOrNull()?.let { it.email ?: it.nickname }.orEmpty()

    companion object {
        const val DEFAULT_ISSUER = "OpenCloudreve"

        private fun isOtp(code: String) = code.length == 6 && code.all { it.isDigit() }

        /** `otpauth://totp/<issuer>:<account>?secret=<secret>&issuer=<issuer>`（标签与参数均按 URI 组件编码） */
        fun otpauthUrl(issuer: String, account: String, secret: String): String {
            val i = UriCodec.encodeComponent(issuer)
            return "otpauth://totp/$i:${UriCodec.encodeComponent(account)}?secret=${UriCodec.encodeComponent(secret)}&issuer=$i"
        }
    }
}
