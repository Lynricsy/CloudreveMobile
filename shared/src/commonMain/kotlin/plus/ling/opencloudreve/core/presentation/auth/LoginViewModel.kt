package plus.ling.opencloudreve.core.presentation.auth

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Job
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.endpoints.SiteApi
import plus.ling.opencloudreve.core.api.endpoints.UserApi
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.LoginFlow
import plus.ling.opencloudreve.core.auth.LoginStep
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.PkceAuthorization
import plus.ling.opencloudreve.core.auth.PkceOAuth
import plus.ling.opencloudreve.core.auth.SiteUrl
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import plus.ling.opencloudreve.core.model.CaptchaType
import plus.ling.opencloudreve.core.model.SiteLogin
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError

/** 登录页参数 */
data class LoginParams(
    val siteUrl: String,
    /** 预填邮箱（会话失效后重新登录） */
    val email: String? = null,
    /** 注册/找回密码邮件使用的语言（如 `zh-CN`、`en-US`），null 由服务端决定 */
    val language: String? = null,
)

/** 登录页的阶段 */
enum class LoginStage {
    /** 输入邮箱（也可直接用 Passkey / 网页登录） */
    Email,

    /** 输入密码 */
    Password,

    /** 输入 6 位 TOTP */
    TwoFactor,

    /** 注册 */
    Register,

    /** 发送重置密码邮件 */
    ForgotPassword,
}

/** 当前阶段的验证码呈现方式 */
enum class CaptchaKind {
    /** 不需要验证码 */
    None,

    /** 图形验证码（`normal`，原生渲染 [CaptchaState.image]，点图刷新） */
    Image,

    /** recaptcha / turnstile / cap：WebView 加载 [LoginUiState.captchaPageUrl]，收到 JSON 后发 [LoginIntent.WebCaptchaSolved] */
    Web,

    /** 需要 Web 验证码但服务端 < 4.19.11（没有 /captcha 页）：提示“请在网页端登录” */
    Unsupported,
}

/** 验证码状态 */
data class CaptchaState(
    val kind: CaptchaKind = CaptchaKind.None,
    /** 图形验证码 PNG 的 base64（已去掉 `data:...;base64,` 前缀） */
    val image: String? = null,
    val ticket: String? = null,
    /** 用户输入的图形验证码 */
    val answer: String = "",
    /** Web 验证码回传的参数 */
    val webToken: CaptchaParams? = null,
    /** 每次需要重新验证（失败、切换阶段）时递增；Web 验证码据此重新加载 WebView */
    val generation: Int = 0,
    val loading: Boolean = false,
    /** 图形验证码加载失败 */
    val loadError: UiError? = null,
) {
    /** 已满足提交条件 */
    val isReady: Boolean
        get() = when (kind) {
            CaptchaKind.None -> true
            CaptchaKind.Image -> ticket != null && answer.isNotBlank()
            CaptchaKind.Web -> webToken != null
            CaptchaKind.Unsupported -> false
        }

    internal fun toParams(): CaptchaParams? = when (kind) {
        CaptchaKind.Image -> CaptchaParams(captcha = answer.trim(), ticket = ticket)
        CaptchaKind.Web -> webToken
        else -> null
    }
}

/** 一次性提示 */
enum class LoginNotice {
    /** 注册成功，可直接登录 */
    Registered,

    /** 注册成功，需要先点击邮件中的激活链接 */
    RegisteredActivationRequired,

    /** 重置密码邮件已发送 */
    ResetEmailSent,
}

data class LoginUiState(
    val siteUrl: String,
    val siteTitle: String = "",
    val logoUrl: String? = null,
    val logoLightUrl: String? = null,
    val serverVersion: String? = null,
    /** 服务端 ≥ 4.19.11：可用 Passkey、网页登录（OAuth）、Web 验证码页 */
    val enhanced: Boolean = false,
    val loadingConfig: Boolean = true,
    /** 站点配置加载失败（可发 [LoginIntent.RetryConfig]） */
    val configError: UiError? = null,
    val registerEnabled: Boolean = false,
    /** 站点启用了 Passkey（Android 原生 Passkey 入口需要它且 [enhanced]） */
    val sitePasskeyEnabled: Boolean = false,
    val tosUrl: String? = null,
    val privacyPolicyUrl: String? = null,
    val stage: LoginStage = LoginStage.Email,
    val email: String = "",
    val password: String = "",
    /** 注册时的确认密码 */
    val passwordConfirm: String = "",
    val otp: String = "",
    /** 该用户可用密码登录（prepare 结果） */
    val passwordEnabled: Boolean = true,
    /** 该用户注册过 Passkey（prepare 结果） */
    val userHasPasskey: Boolean = false,
    val captcha: CaptchaState = CaptchaState(),
    val submitting: Boolean = false,
    val error: UiError? = null,
    val notice: LoginNotice? = null,
) {
    val canGoBack: Boolean get() = stage != LoginStage.Email
    val canContinue: Boolean get() = !submitting && !loadingConfig && email.isNotBlank()
    val canSubmitPassword: Boolean get() = !submitting && password.isNotEmpty() && captcha.isReady
    val canSubmitOtp: Boolean get() = !submitting && otp.length == OTP_LENGTH
    val passwordMismatch: Boolean get() = passwordConfirm.isNotEmpty() && password != passwordConfirm
    val canSubmitRegister: Boolean
        get() = !submitting && email.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH &&
            password == passwordConfirm && captcha.isReady
    val canSubmitForgot: Boolean get() = !submitting && email.isNotBlank() && captcha.isReady

    /** WebView 验证码页地址：`<site>/captcha?theme=dark|light&lng=<语言>` */
    fun captchaPageUrl(dark: Boolean, language: String): String =
        "$siteUrl/captcha?theme=${if (dark) "dark" else "light"}&lng=${language.encodeURLParameter()}"

    companion object {
        const val OTP_LENGTH = 6
        const val MIN_PASSWORD_LENGTH = 6
    }
}

sealed interface LoginIntent {
    data object RetryConfig : LoginIntent
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data class PasswordConfirmChanged(val value: String) : LoginIntent

    /** 只保留数字，输满 6 位自动提交 */
    data class OtpChanged(val value: String) : LoginIntent
    data class CaptchaAnswerChanged(val value: String) : LoginIntent

    /** 重新获取图形验证码 / 重新加载 Web 验证码 */
    data object RefreshCaptcha : LoginIntent

    /** Web 验证码页回传的 JSON（`{captcha?, ticket?}`） */
    data class WebCaptchaSolved(val json: String) : LoginIntent

    /** 邮箱阶段“下一步”：`GET /session/prepare` */
    data object ContinueWithEmail : LoginIntent
    data object SubmitPassword : LoginIntent
    data object SubmitOtp : LoginIntent
    data object OpenRegister : LoginIntent
    data object SubmitRegister : LoginIntent
    data object OpenForgotPassword : LoginIntent
    data object SubmitForgotPassword : LoginIntent

    /** 返回邮箱阶段（[LoginUiState.canGoBack] 为 false 时忽略） */
    data object Back : LoginIntent

    /** Android：开始 Passkey 登录，随后收到 [LoginEffect.LaunchPasskey] */
    data object StartPasskey : LoginIntent

    /** Credential Manager 返回的 `authenticationResponseJson` 原样传入 */
    data class PasskeyCompleted(val responseJson: String) : LoginIntent

    /** Passkey 失败；[cancelled] 为用户取消时不提示错误 */
    data class PasskeyFailed(val message: String?, val cancelled: Boolean) : LoginIntent

    /** iOS：开始网页登录（OAuth PKCE），随后收到 [LoginEffect.LaunchWebLogin] */
    data object StartWebLogin : LoginIntent

    /** 网页登录回调地址（`opencloudreve://oauth/callback?code=&state=`） */
    data class WebLoginCallback(val url: String) : LoginIntent
    data object WebLoginCancelled : LoginIntent
    data object DismissError : LoginIntent
    data object DismissNotice : LoginIntent
}

sealed interface LoginEffect {
    /** 用 [requestJson]（`options.publicKey`）调起系统 Passkey */
    data class LaunchPasskey(val requestJson: String) : LoginEffect

    /** 用系统认证会话打开 [authorizeUrl]，回调 scheme 为 [callbackScheme] */
    data class LaunchWebLogin(val authorizeUrl: String, val callbackScheme: String) : LoginEffect

    /** 登录完成，账户已保存并设为当前账户 */
    data class SignedIn(val accountId: String) : LoginEffect
}

/**
 * 登录页（两段式）：邮箱 → 密码（可能带验证码）→ 可能的 2FA；另含注册、找回密码、Passkey、网页登录。
 * 验证码类型取自 basic 配置的 `captcha_type`，是否需要取自 login 配置的 `login_captcha` / `reg_captcha` / `forget_captcha`。
 */
class LoginViewModel(
    params: LoginParams,
    private val registry: ApiClientRegistry,
    private val loginFlow: LoginFlow,
    private val passkeyFlow: PasskeyFlow,
    private val pkce: PkceOAuth,
) : CoreViewModel<LoginUiState, LoginIntent, LoginEffect>(
    LoginUiState(siteUrl = SiteUrl.normalize(params.siteUrl), email = params.email.orEmpty()),
) {
    private val language = params.language
    private val siteUrl = currentState.siteUrl
    private var siteLogin = SiteLogin()
    private var captchaType = CaptchaType.NORMAL
    private var twoFactorSession: String? = null
    private var passkeySession: String? = null
    private var pendingOAuth: PkceAuthorization? = null
    private var captchaJob: Job? = null

    init {
        loadConfig()
    }

    override fun on(intent: LoginIntent) {
        when (intent) {
            LoginIntent.RetryConfig -> loadConfig()
            is LoginIntent.EmailChanged -> updateState { it.copy(email = intent.value, error = null) }
            is LoginIntent.PasswordChanged -> updateState { it.copy(password = intent.value, error = null) }
            is LoginIntent.PasswordConfirmChanged -> updateState { it.copy(passwordConfirm = intent.value, error = null) }
            is LoginIntent.OtpChanged -> onOtpChanged(intent.value)
            is LoginIntent.CaptchaAnswerChanged ->
                updateState { it.copy(captcha = it.captcha.copy(answer = intent.value), error = null) }
            LoginIntent.RefreshCaptcha -> refreshCaptcha()
            is LoginIntent.WebCaptchaSolved -> onWebCaptcha(intent.json)
            LoginIntent.ContinueWithEmail -> continueWithEmail()
            LoginIntent.SubmitPassword -> submitPassword()
            LoginIntent.SubmitOtp -> submitOtp()
            LoginIntent.OpenRegister -> enterStage(LoginStage.Register)
            LoginIntent.SubmitRegister -> submitRegister()
            LoginIntent.OpenForgotPassword -> enterStage(LoginStage.ForgotPassword)
            LoginIntent.SubmitForgotPassword -> submitForgot()
            LoginIntent.Back -> if (currentState.canGoBack) enterStage(LoginStage.Email)
            LoginIntent.StartPasskey -> startPasskey()
            is LoginIntent.PasskeyCompleted -> finishPasskey(intent.responseJson)
            is LoginIntent.PasskeyFailed -> {
                passkeySession = null
                updateState {
                    it.copy(
                        submitting = false,
                        error = if (intent.cancelled) null else UiError(ErrorKind.Unknown, null, intent.message),
                    )
                }
            }
            LoginIntent.StartWebLogin -> startWebLogin()
            is LoginIntent.WebLoginCallback -> finishWebLogin(intent.url)
            LoginIntent.WebLoginCancelled -> {
                pendingOAuth = null
                updateState { it.copy(submitting = false) }
            }
            LoginIntent.DismissError -> updateState { it.copy(error = null) }
            LoginIntent.DismissNotice -> updateState { it.copy(notice = null) }
        }
    }

    // region 站点配置

    private fun loadConfig() {
        updateState { it.copy(loadingConfig = true, configError = null) }
        launchCatching(onError = { e -> updateState { it.copy(loadingConfig = false, configError = e) } }) {
            val info = probeServer(registry, siteUrl)
            val login = SiteApi(registry.anonymous(siteUrl)).loginConfig()
            siteLogin = login
            captchaType = info.basic.captchaType
            updateState {
                it.copy(
                    siteTitle = info.title,
                    logoUrl = info.logoUrl,
                    logoLightUrl = info.logoLightUrl,
                    serverVersion = info.version,
                    enhanced = info.enhanced,
                    loadingConfig = false,
                    registerEnabled = login.registerEnabled,
                    sitePasskeyEnabled = login.authn,
                    tosUrl = login.tosUrl?.takeIf { url -> url.isNotBlank() },
                    privacyPolicyUrl = login.privacyPolicyUrl?.takeIf { url -> url.isNotBlank() },
                )
            }
            // 配置到达时可能已处于需要验证码的阶段
            prepareCaptcha(currentState.stage)
        }
    }

    // endregion

    // region 阶段切换与验证码

    private fun enterStage(stage: LoginStage) {
        if (stage == LoginStage.Email) {
            twoFactorSession = null
        }
        updateState {
            it.copy(
                stage = stage,
                error = null,
                otp = "",
                password = if (stage == LoginStage.Email) "" else it.password,
                passwordConfirm = "",
            )
        }
        prepareCaptcha(stage)
    }

    private fun captchaRequired(stage: LoginStage): Boolean = when (stage) {
        LoginStage.Password -> siteLogin.loginCaptcha
        LoginStage.Register -> siteLogin.regCaptcha
        LoginStage.ForgotPassword -> siteLogin.forgetCaptcha
        LoginStage.Email, LoginStage.TwoFactor -> false
    }

    private fun captchaKindFor(stage: LoginStage): CaptchaKind {
        if (!captchaRequired(stage)) return CaptchaKind.None
        return when (captchaType) {
            CaptchaType.RECAPTCHA, CaptchaType.TURNSTILE, CaptchaType.CAP ->
                if (currentState.enhanced) CaptchaKind.Web else CaptchaKind.Unsupported
            // normal、已弃用的 tcaptcha 与未知类型都按图形验证码处理
            else -> CaptchaKind.Image
        }
    }

    /** 进入阶段或配置到达时按需准备验证码 */
    private fun prepareCaptcha(stage: LoginStage) {
        val kind = captchaKindFor(stage)
        updateState {
            it.copy(captcha = CaptchaState(kind = kind, generation = it.captcha.generation + 1))
        }
        if (kind == CaptchaKind.Image) loadImageCaptcha()
    }

    /** 失败后刷新：图形验证码重新获取，Web 验证码递增 generation 让平台重载 */
    private fun refreshCaptcha() {
        when (currentState.captcha.kind) {
            CaptchaKind.Image -> loadImageCaptcha()
            CaptchaKind.Web -> updateState {
                it.copy(captcha = it.captcha.copy(webToken = null, generation = it.captcha.generation + 1))
            }
            CaptchaKind.None, CaptchaKind.Unsupported -> Unit
        }
    }

    private fun loadImageCaptcha() {
        captchaJob?.cancel()
        updateState { it.copy(captcha = it.captcha.copy(loading = true, loadError = null, answer = "")) }
        captchaJob = launchCatching(onError = { e ->
            updateState { it.copy(captcha = it.captcha.copy(loading = false, loadError = e)) }
        }) {
            val img = SiteApi(registry.anonymous(siteUrl)).captcha()
            updateState {
                it.copy(
                    captcha = it.captcha.copy(
                        image = img.image.substringAfter("base64,"),
                        ticket = img.ticket,
                        answer = "",
                        loading = false,
                    ),
                )
            }
        }
    }

    private fun onWebCaptcha(json: String) {
        val params = runCatching { CloudreveJson.decodeFromString(CaptchaParams.serializer(), json) }.getOrNull()
        // 过期/重置时验证码组件会回传空参数，此时视为未完成
        val token = params?.takeIf { !it.captcha.isNullOrEmpty() || !it.ticket.isNullOrEmpty() }
        updateState { it.copy(captcha = it.captcha.copy(webToken = token), error = null) }
    }

    // endregion

    // region 密码登录与 2FA

    private fun continueWithEmail() {
        val s = currentState
        if (!s.canContinue) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::fail) {
            val step = loginFlow.prepare(siteUrl, s.email)
            updateState {
                it.copy(
                    submitting = false,
                    passwordEnabled = step.passwordEnabled,
                    userHasPasskey = step.webauthnEnabled,
                )
            }
            enterStage(LoginStage.Password)
        }
    }

    private fun submitPassword() {
        val s = currentState
        if (!s.canSubmitPassword) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::failWithCaptchaRefresh) {
            when (val step = loginFlow.login(siteUrl, s.email, s.password, s.captcha.toParams())) {
                is LoginStep.TwoFactor -> {
                    twoFactorSession = step.sessionId
                    updateState { it.copy(submitting = false) }
                    enterStage(LoginStage.TwoFactor)
                }
                is LoginStep.Done -> signedIn(step.account.id)
                is LoginStep.Password -> updateState { it.copy(submitting = false) }
            }
        }
    }

    private fun onOtpChanged(value: String) {
        val otp = value.filter { it.isDigit() }.take(LoginUiState.OTP_LENGTH)
        updateState { it.copy(otp = otp, error = null) }
        if (otp.length == LoginUiState.OTP_LENGTH) submitOtp()
    }

    private fun submitOtp() {
        val s = currentState
        val session = twoFactorSession ?: return
        if (!s.canSubmitOtp) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = { e ->
            // 2FA 会话过期（404）只能回到密码阶段重新登录
            if (e.code == ErrorCode.NOT_FOUND) {
                twoFactorSession = null
                updateState { it.copy(submitting = false) }
                enterStage(LoginStage.Password)
                updateState { it.copy(error = e) }
            } else {
                updateState { it.copy(submitting = false, otp = "", error = e) }
            }
        }) {
            val done = loginFlow.verify2fa(siteUrl, session, s.otp)
            signedIn(done.account.id)
        }
    }

    // endregion

    // region 注册与找回密码

    private fun submitRegister() {
        val s = currentState
        if (!s.canSubmitRegister) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::failWithCaptchaRefresh) {
            val notice = try {
                UserApi(registry.anonymous(siteUrl)).register(s.email.trim(), s.password, language, s.captcha.toParams())
                LoginNotice.Registered
            } catch (e: ApiException) {
                // 203：注册成功但需要邮件激活
                if (e.code != ErrorCode.NOT_FULLY_SUCCESS) throw e
                LoginNotice.RegisteredActivationRequired
            }
            updateState { it.copy(submitting = false, notice = notice) }
            enterStage(LoginStage.Password)
            // 回到密码阶段时保留刚注册的密码，方便直接登录
            updateState { it.copy(password = s.password) }
        }
    }

    private fun submitForgot() {
        val s = currentState
        if (!s.canSubmitForgot) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::failWithCaptchaRefresh) {
            UserApi(registry.anonymous(siteUrl)).sendResetEmail(s.email.trim(), language, s.captcha.toParams())
            updateState { it.copy(submitting = false, notice = LoginNotice.ResetEmailSent) }
            enterStage(LoginStage.Email)
        }
    }

    // endregion

    // region Passkey 与网页登录

    private fun startPasskey() {
        if (currentState.submitting) return
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::fail) {
            val challenge = passkeyFlow.beginLogin(siteUrl)
            passkeySession = challenge.sessionId
            sendEffect(LoginEffect.LaunchPasskey(challenge.requestJson))
        }
    }

    private fun finishPasskey(responseJson: String) {
        val session = passkeySession ?: return
        passkeySession = null
        launchCatching(onError = ::fail) {
            val account = passkeyFlow.finishLogin(siteUrl, session, responseJson)
            signedIn(account.id)
        }
    }

    private fun startWebLogin() {
        if (currentState.submitting) return
        val auth = pkce.begin(siteUrl)
        pendingOAuth = auth
        updateState { it.copy(submitting = true, error = null) }
        sendEffect(LoginEffect.LaunchWebLogin(auth.authorizeUrl, PkceOAuth.CALLBACK_SCHEME))
    }

    private fun finishWebLogin(url: String) {
        val auth = pendingOAuth ?: return
        pendingOAuth = null
        updateState { it.copy(submitting = true, error = null) }
        launchCatching(onError = ::fail) {
            val code = pkce.parseCallback(url, auth.state)
            val account = pkce.exchange(auth.siteUrl, code, auth.codeVerifier)
            signedIn(account.id)
        }
    }

    // endregion

    private fun signedIn(accountId: String) {
        updateState { it.copy(submitting = false, password = "", otp = "") }
        sendEffect(LoginEffect.SignedIn(accountId))
    }

    private fun fail(e: UiError) {
        updateState { it.copy(submitting = false, error = e) }
    }

    /** 失败后验证码已被服务端消耗（或答错 40026），一律刷新 */
    private fun failWithCaptchaRefresh(e: UiError) {
        fail(e)
        refreshCaptcha()
    }
}
