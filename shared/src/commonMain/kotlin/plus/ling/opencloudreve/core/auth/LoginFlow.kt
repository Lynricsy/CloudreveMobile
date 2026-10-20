package plus.ling.opencloudreve.core.auth

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonPrimitive
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import plus.ling.opencloudreve.core.auth.model.LoginResponse
import plus.ling.opencloudreve.core.auth.model.PasswordLoginRequest
import plus.ling.opencloudreve.core.auth.model.PrepareLoginResponse
import plus.ling.opencloudreve.core.auth.model.TwoFactorLoginRequest
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.model.User

/** 登录流程的阶段 */
sealed class LoginStep {
    /** 邮箱已确认存在：可用密码（[passwordEnabled]）和/或 Passkey（[webauthnEnabled]，该用户已注册过 Passkey） */
    data class Password(val passwordEnabled: Boolean, val webauthnEnabled: Boolean) : LoginStep()

    /** 需要输入 6 位 TOTP，随后调用 [LoginFlow.verify2fa]（会话 600 秒有效） */
    data class TwoFactor(val sessionId: String) : LoginStep()

    /** 登录完成，账户已保存并设为当前账户 */
    data class Done(val account: Account) : LoginStep()
}

/**
 * 邮箱密码登录（两段式）+ 2FA。错误以 [ApiException] 抛出，常见 code：
 * 404 邮箱不存在（prepare）、40080 邮箱或密码错误、40017 已封禁、40018 未激活、
 * 40026 验证码错误（需刷新验证码）、40022 OTP 错误、404 2FA 会话过期。
 */
class LoginFlow internal constructor(
    private val registry: ApiClientRegistry,
    private val accounts: AccountRepository,
) {
    /** `GET /session/prepare?email=` */
    @Throws(Exception::class)
    suspend fun prepare(siteUrl: String, email: String): LoginStep.Password {
        val res = registry.anonymous(siteUrl).call(
            HttpMethod.Get,
            "/session/prepare",
            PrepareLoginResponse.serializer(),
            query = mapOf("email" to email.trim()),
            noAuth = true,
        )
        return LoginStep.Password(passwordEnabled = res.passwordEnabled, webauthnEnabled = res.webauthnEnabled)
    }

    /** `POST /session/token`；返回 [LoginStep.TwoFactor]（code 203）或 [LoginStep.Done] */
    @Throws(Exception::class)
    suspend fun login(siteUrl: String, email: String, password: String, captcha: CaptchaParams? = null): LoginStep {
        val body = PasswordLoginRequest(
            email = email.trim(),
            password = password,
            captcha = captcha?.captcha,
            ticket = captcha?.ticket,
        )
        val res = try {
            registry.anonymous(siteUrl).call(
                HttpMethod.Post,
                "/session/token",
                LoginResponse.serializer(),
                body = CloudreveJson.encodeToJsonElement(PasswordLoginRequest.serializer(), body),
                noAuth = true,
            )
        } catch (e: ApiException) {
            val sessionId = (e.data as? JsonPrimitive)?.content
            if (e.code == ErrorCode.NOT_FULLY_SUCCESS && !sessionId.isNullOrEmpty()) {
                return LoginStep.TwoFactor(sessionId)
            }
            throw e
        }
        return LoginStep.Done(finishLogin(siteUrl, res.user, res.token))
    }

    /** `POST /session/token/2fa` */
    @Throws(Exception::class)
    suspend fun verify2fa(siteUrl: String, sessionId: String, otp: String): LoginStep.Done {
        val res = registry.anonymous(siteUrl).call(
            HttpMethod.Post,
            "/session/token/2fa",
            LoginResponse.serializer(),
            body = CloudreveJson.encodeToJsonElement(
                TwoFactorLoginRequest.serializer(),
                TwoFactorLoginRequest(otp = otp.trim(), sessionId = sessionId),
            ),
            noAuth = true,
        )
        return LoginStep.Done(finishLogin(siteUrl, res.user, res.token))
    }

    /** 保存账户与令牌并设为当前账户（Passkey、OAuth、会话交接等其它登录方式也走这里） */
    @Throws(Exception::class)
    fun finishLogin(siteUrl: String, user: User, token: Token): Account =
        accounts.addOrUpdate(siteUrl, user, token, activate = true)
}
