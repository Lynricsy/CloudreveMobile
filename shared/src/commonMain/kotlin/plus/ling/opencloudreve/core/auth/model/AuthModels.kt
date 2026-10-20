package plus.ling.opencloudreve.core.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.model.User

/** `GET /session/prepare?email=` 响应 */
@Serializable
data class PrepareLoginResponse(
    @SerialName("webauthn_enabled") val webauthnEnabled: Boolean = false,
    @SerialName("password_enabled") val passwordEnabled: Boolean = true,
)

/** 密码登录 / 2FA / Passkey 登录 / 会话交接成功后的 `{user, token}` */
@Serializable
data class LoginResponse(
    val user: User,
    val token: Token,
)

/** `POST /session/token` 请求体（验证码字段平铺在同一 JSON 中） */
@Serializable
data class PasswordLoginRequest(
    val email: String,
    val password: String,
    val captcha: String? = null,
    val ticket: String? = null,
)

/** `POST /session/token/2fa` 请求体 */
@Serializable
data class TwoFactorLoginRequest(
    val otp: String,
    @SerialName("session_id") val sessionId: String,
)

/** `POST /session/token/refresh`、`DELETE /session/token` 请求体 */
@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/**
 * 验证码参数，平铺进登录/注册/重置请求 JSON：
 * - normal：`captcha` = 用户输入，`ticket` = `/site/captcha` 的 ticket
 * - recaptcha：只有 `captcha`
 * - turnstile / cap：只有 `ticket`
 */
@Serializable
data class CaptchaParams(
    val captcha: String? = null,
    val ticket: String? = null,
)

/** `PUT /session/authn` 响应：`options.publicKey` 为 WebAuthn 断言请求选项 */
@Serializable
data class PasskeyLoginOptions(
    val options: JsonObject,
    @SerialName("session_id") val sessionId: String,
)

/** `POST /session/authn` 请求体（`response` 为凭证 JSON 序列化后的字符串） */
@Serializable
data class PasskeyLoginRequest(
    val response: String,
    @SerialName("session_id") val sessionId: String,
)

/** `POST /user/authn` 请求体（注册 Passkey；`name` 中 `{os}`/`{browser}` 由服务端按 `ua` 替换） */
@Serializable
data class PasskeyRegisterRequest(
    val response: String,
    val name: String,
    val ua: String,
)

/** `POST /session/oauth/token` 成功响应（无信封） */
@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

/** `POST /session/oauth/token` 失败响应（HTTP 400，无信封） */
@Serializable
data class OAuthErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_codes") val errorCodes: List<Int> = emptyList(),
    @SerialName("correlation_id") val correlationId: String? = null,
)
