package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import plus.ling.opencloudreve.core.auth.model.LoginResponse
import plus.ling.opencloudreve.core.auth.model.OAuthErrorResponse
import plus.ling.opencloudreve.core.auth.model.OAuthTokenResponse
import plus.ling.opencloudreve.core.auth.model.PasskeyLoginOptions
import plus.ling.opencloudreve.core.auth.model.PasskeyLoginRequest
import plus.ling.opencloudreve.core.auth.model.PasswordLoginRequest
import plus.ling.opencloudreve.core.auth.model.PrepareLoginResponse
import plus.ling.opencloudreve.core.auth.model.RefreshTokenRequest
import plus.ling.opencloudreve.core.auth.model.TwoFactorLoginRequest
import plus.ling.opencloudreve.core.model.HandoffCode
import plus.ling.opencloudreve.core.model.HandoffExchangeRequest
import plus.ling.opencloudreve.core.model.OAuthApp
import plus.ling.opencloudreve.core.model.Token

/** 会话接口（`/session/…`）。登录类请求体与响应模型归 `core.auth.model` */
class SessionApi(private val client: ApiClient) {

    /** `GET /session/prepare?email=`；邮箱不存在返回 404 */
    @Throws(Exception::class)
    suspend fun prepare(email: String): PrepareLoginResponse =
        client.get("/session/prepare", mapOf("email" to email), noAuth = true)

    /**
     * `POST /session/token` 密码登录；验证码字段平铺进同一 JSON。
     * 需要 2FA 时抛 [ApiException]（`code == 203`，`data` 为 2FA session id 字符串）。
     */
    @Throws(Exception::class)
    suspend fun login(email: String, password: String, captcha: CaptchaParams? = null): LoginResponse =
        client.post(
            "/session/token",
            PasswordLoginRequest(email, password, captcha?.captcha, captcha?.ticket),
            noAuth = true,
        )

    /** `POST /session/token/2fa`；OTP 错误 40022，session 过期 404 */
    @Throws(Exception::class)
    suspend fun login2fa(sessionId: String, otp: String): LoginResponse =
        client.post("/session/token/2fa", TwoFactorLoginRequest(otp, sessionId), noAuth = true)

    /** `POST /session/token/refresh`；任何失败为 40020。每次都会返回新的 refresh token */
    @Throws(Exception::class)
    suspend fun refresh(refreshToken: String): Token =
        client.post("/session/token/refresh", RefreshTokenRequest(refreshToken), noAuth = true)

    /** `DELETE /session/token`：吊销该 refresh token 所属会话 */
    @Throws(Exception::class)
    suspend fun signOut(refreshToken: String) {
        client.delete<Unit, RefreshTokenRequest>("/session/token", RefreshTokenRequest(refreshToken), noAuth = true)
    }

    /** `PUT /session/authn`：开始可发现凭证 Passkey 登录，`options.publicKey` 交给平台凭证 API */
    @Throws(Exception::class)
    suspend fun authnBegin(): PasskeyLoginOptions =
        client.call(HttpMethod.Put, "/session/authn", PasskeyLoginOptions.serializer(), noAuth = true)

    /** `POST /session/authn`：[response] 为平台返回的断言 JSON 字符串原样提交 */
    @Throws(Exception::class)
    suspend fun authnFinish(sessionId: String, response: String): LoginResponse =
        client.post("/session/authn", PasskeyLoginRequest(response, sessionId), noAuth = true)

    /** `GET /session/oauth/app/:id`：OAuth 客户端信息 */
    @Throws(Exception::class)
    suspend fun oauthApp(clientId: String): OAuthApp = client.get("/session/oauth/app/$clientId")

    /**
     * `POST /session/oauth/token`（表单提交，**无信封**）：用授权码 + PKCE verifier 换 token。
     * 失败时服务端返回 HTTP 400 `{error, error_description, error_codes}`，转换为 [ApiException]
     * （code 取 `error_codes[0]`，缺失时为 40020）。
     */
    @Throws(Exception::class)
    suspend fun oauthToken(
        clientId: String,
        clientSecret: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): OAuthTokenResponse {
        val form = Parameters.build {
            append("client_id", clientId)
            append("client_secret", clientSecret)
            append("grant_type", "authorization_code")
            append("code", code)
            append("code_verifier", codeVerifier)
            append("redirect_uri", redirectUri)
        }
        val response = try {
            client.http.request(client.apiBase + "/session/oauth/token") {
                method = HttpMethod.Post
                with(client) { applyClientHeaders(null) }
                setBody(FormDataContent(form))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "network error", cause = e)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "read error", cause = e)
        }
        val status = response.status.value
        if (status in 200..299) {
            return try {
                CloudreveJson.decodeFromString(OAuthTokenResponse.serializer(), text)
            } catch (e: Exception) {
                throw TransportException("Unexpected OAuth token response (HTTP $status)", status, e)
            }
        }
        val err = runCatching { CloudreveJson.decodeFromString(OAuthErrorResponse.serializer(), text) }.getOrNull()
            ?: throw TransportException("Unexpected OAuth token response (HTTP $status)", status)
        throw ApiException(
            code = err.errorCodes.firstOrNull() ?: ErrorCode.CREDENTIAL_INVALID,
            serverMsg = err.errorDescription ?: err.error ?: "",
            correlationId = err.correlationId,
        )
    }

    /** `DELETE /session/oauth/grant/:client_id`：撤销对某 OAuth 应用的授权 */
    @Throws(Exception::class)
    suspend fun revokeOAuthGrant(clientId: String) {
        client.deleteNoBody<Unit>("/session/oauth/grant/$clientId")
    }

    /** `POST /session/handoff`（服务端 ≥ 4.19.11）：生成 60 秒有效的一次性网页交接码 */
    @Throws(Exception::class)
    suspend fun handoffCreate(): HandoffCode =
        client.call(HttpMethod.Post, "/session/handoff", HandoffCode.serializer(), body = JsonObject(emptyMap()))

    /** `POST /session/handoff/exchange`：用交接码换取完整会话（与密码登录同形） */
    @Throws(Exception::class)
    suspend fun handoffExchange(code: String): LoginResponse =
        client.post("/session/handoff/exchange", HandoffExchangeRequest(code), noAuth = true)
}
