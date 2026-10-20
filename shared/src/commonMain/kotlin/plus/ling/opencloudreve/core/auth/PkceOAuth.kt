package plus.ling.opencloudreve.core.auth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import io.ktor.utils.io.CancellationException
import org.kotlincrypto.hash.sha2.SHA256
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.auth.model.OAuthErrorResponse
import plus.ling.opencloudreve.core.auth.model.OAuthTokenResponse
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.model.User
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 一次 OAuth 授权请求：打开 [authorizeUrl]，回调后用 [state] 校验、用 [codeVerifier] 换 token */
data class PkceAuthorization(
    val siteUrl: String,
    val authorizeUrl: String,
    val state: String,
    val codeVerifier: String,
)

/** OAuth 回调错误（state 不匹配、用户拒绝、缺少 code） */
class OAuthCallbackException(message: String) : CloudreveException(message)

/**
 * OAuth 2.0 授权码 + PKCE(S256)，使用服务端内置的移动端 client（iOS Passkey / 网页登录路径）：
 * 1. [begin] 生成 verifier/state 与授权地址，交给 `ASWebAuthenticationSession`；
 * 2. 回调 `opencloudreve://oauth/callback?code=&state=` 交给 [parseCallback]；
 * 3. [exchange] 调 `POST /session/oauth/token`（表单、失败 HTTP 400 无信封），再 `GET /user/me` 并保存账户。
 */
class PkceOAuth internal constructor(
    private val registry: ApiClientRegistry,
    private val accounts: AccountRepository,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    /** 生成授权请求 */
    fun begin(siteUrl: String): PkceAuthorization {
        val site = SiteUrl.normalize(siteUrl)
        val verifier = generateCodeVerifier()
        val state = Uuid.random().toHexString()
        val query = listOf(
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
            "scope" to SCOPES.joinToString(" "),
            "code_challenge" to codeChallenge(verifier),
            "code_challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        return PkceAuthorization(site, "$site/session/authorize?$query", state, verifier)
    }

    /** 解析回调地址并校验 state，返回授权码 */
    @Throws(OAuthCallbackException::class)
    fun parseCallback(callbackUrl: String, expectedState: String): String {
        val params = parseQueryString(callbackUrl.substringAfter('?', "").substringBefore('#'))
        if (params["state"] != expectedState) throw OAuthCallbackException("OAuth state mismatch")
        params["error"]?.let { throw OAuthCallbackException("OAuth error: $it") }
        return params["code"]?.takeIf { it.isNotEmpty() } ?: throw OAuthCallbackException("OAuth callback without code")
    }

    /** 用授权码换取令牌，补全用户信息并保存为当前账户 */
    @Throws(Exception::class)
    suspend fun exchange(siteUrl: String, code: String, codeVerifier: String): Account {
        val client = registry.anonymous(siteUrl)
        val now = clock()
        val oauth = requestToken(siteUrl, code, codeVerifier)
        val refresh = oauth.refreshToken
            ?: throw ApiException(ErrorCode.CREDENTIAL_INVALID, "OAuth response has no refresh_token")
        val token = Token(
            accessToken = oauth.accessToken,
            refreshToken = refresh,
            accessExpires = now + oauth.expiresIn.seconds,
            refreshExpires = now + oauth.refreshTokenExpiresIn.seconds,
        )
        val user = client.call(
            HttpMethod.Get,
            "/user/me",
            User.serializer(),
            noAuth = true,
            headers = mapOf(HttpHeaders.Authorization to "Bearer ${token.accessToken}"),
        )
        return accounts.addOrUpdate(siteUrl, user, token, activate = true)
    }

    private suspend fun requestToken(siteUrl: String, code: String, codeVerifier: String): OAuthTokenResponse {
        val client = registry.anonymous(siteUrl)
        val form = parameters {
            append("client_id", CLIENT_ID)
            append("client_secret", CLIENT_SECRET)
            append("grant_type", "authorization_code")
            append("code", code)
            append("code_verifier", codeVerifier)
            append("redirect_uri", REDIRECT_URI)
        }
        val (status, text) = try {
            val response = client.http.submitForm(client.apiBase + "/session/oauth/token", form) {
                with(client) { applyClientHeaders(null) }
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "network error", cause = e)
        }
        if (status == 200) {
            return try {
                CloudreveJson.decodeFromString(OAuthTokenResponse.serializer(), text)
            } catch (e: Exception) {
                throw TransportException("Unexpected OAuth token response", status, e)
            }
        }
        val error = runCatching { CloudreveJson.decodeFromString(OAuthErrorResponse.serializer(), text) }.getOrNull()
            ?: throw TransportException("Unexpected response (HTTP $status)", status)
        throw ApiException(
            code = error.errorCodes.firstOrNull() ?: ErrorCode.CREDENTIAL_INVALID,
            serverMsg = error.errorDescription ?: error.error ?: "OAuth token exchange failed",
            correlationId = error.correlationId,
        )
    }

    companion object {
        /** 服务端 4.19.11 起内置的移动端 OAuth client */
        const val CLIENT_ID = "022afa86-ff52-422e-8dfa-fb2eeaf8cfba"
        const val CLIENT_SECRET = "LaUFupO8g1NaDK3WEiyXkWCLgaENdct3"
        const val REDIRECT_URI = "opencloudreve://oauth/callback"
        const val CALLBACK_SCHEME = "opencloudreve"

        /** 申请 client 的全部 scope（OAuth 签发的 token 受 scope 限制） */
        val SCOPES: List<String> = listOf(
            "profile", "email", "openid", "offline_access",
            "UserInfo.Write", "UserSecurityInfo.Write", "Workflow.Write",
            "Files.Write", "Shares.Write", "DavAccount.Write", "Admin.Write",
        )

        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /**
         * 生成 code_verifier：48 字节随机数（3 个 UUIDv4，`Uuid.random` 使用平台 CSPRNG，共 366 位熵）
         * 的 base64url 无填充编码，64 个字符，仅含 `[A-Za-z0-9_-]`（RFC 7636 要求 43–128 个 unreserved 字符）。
         */
        fun generateCodeVerifier(): String {
            val bytes = ByteArray(48)
            repeat(3) { i -> Uuid.random().toByteArray().copyInto(bytes, destinationOffset = i * 16) }
            return base64Url.encode(bytes)
        }

        /** S256：`BASE64URL_NOPAD(SHA256(ascii(verifier)))` */
        fun codeChallenge(verifier: String): String = base64Url.encode(SHA256().digest(verifier.encodeToByteArray()))
    }
}
