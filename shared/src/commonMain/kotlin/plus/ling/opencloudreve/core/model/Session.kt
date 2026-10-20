package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /session/handoff` 响应：一次性会话交接码（60 秒有效） */
@Serializable
data class HandoffCode(val code: String)

/** `POST /session/handoff/exchange` 请求体 */
@Serializable
data class HandoffExchangeRequest(val code: String)

/** `GET /session/oauth/app/:app_id`（`service/oauth/response.go:AppRegistration`） */
@Serializable
data class OAuthApp(
    val id: String,
    val name: String = "",
    @SerialName("homepage_url") val homepageUrl: String? = null,
    val icon: String? = null,
    val description: String? = null,
    /** 仅登录且授权过时有值 */
    @SerialName("consented_scopes") val consentedScopes: List<String> = emptyList(),
)

/**
 * `POST /user` 注册（`service/user/register.go`）。
 * 开启注册验证码时把 `captcha`/`ticket` 一并填入（与登录相同的平铺规则）。
 */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val language: String? = null,
    val captcha: String? = null,
    val ticket: String? = null,
)

/** `POST /user/reset` 发送重置密码邮件（可带验证码字段） */
@Serializable
data class ResetPasswordEmailRequest(
    val email: String,
    val language: String? = null,
    val captcha: String? = null,
    val ticket: String? = null,
)

/** `PATCH /user/reset/:id` 用邮件中的 secret 设置新密码 */
@Serializable
data class ResetPasswordRequest(
    val password: String,
    val secret: String,
)
