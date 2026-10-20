package plus.ling.opencloudreve.core.auth

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.auth.model.LoginResponse
import plus.ling.opencloudreve.core.auth.model.PasskeyLoginOptions
import plus.ling.opencloudreve.core.auth.model.PasskeyLoginRequest
import plus.ling.opencloudreve.core.auth.model.PasskeyRegisterRequest
import plus.ling.opencloudreve.core.model.Passkey

/**
 * Passkey 登录挑战：[requestJson] 是 `options.publicKey`（PublicKeyCredentialRequestOptions JSON），
 * 可直接交给 Android `GetPublicKeyCredentialOption(requestJson)`；[sessionId] 300 秒内有效。
 */
data class PasskeyChallenge(val sessionId: String, val requestJson: String)

/**
 * 原生 Passkey（Android Credential Manager）。服务端需 `authn_enabled`（否则 40019），
 * 且站点已配置本应用签名指纹（否则校验失败 40025）。
 */
class PasskeyFlow internal constructor(
    private val registry: ApiClientRegistry,
    private val accounts: AccountRepository,
) {
    /** `PUT /session/authn`：可发现凭证登录，无需邮箱 */
    @Throws(Exception::class)
    suspend fun beginLogin(siteUrl: String): PasskeyChallenge {
        val res = registry.anonymous(siteUrl).call(
            HttpMethod.Put,
            "/session/authn",
            PasskeyLoginOptions.serializer(),
            noAuth = true,
        )
        return PasskeyChallenge(res.sessionId, publicKeyJson(res.options))
    }

    /** `POST /session/authn`：[responseJson] 为平台返回的断言 JSON 原文（Android `authenticationResponseJson`） */
    @Throws(Exception::class)
    suspend fun finishLogin(siteUrl: String, sessionId: String, responseJson: String): Account {
        val res = registry.anonymous(siteUrl).call(
            HttpMethod.Post,
            "/session/authn",
            LoginResponse.serializer(),
            body = CloudreveJson.encodeToJsonElement(
                PasskeyLoginRequest.serializer(),
                PasskeyLoginRequest(response = responseJson, sessionId = sessionId),
            ),
            noAuth = true,
        )
        return accounts.addOrUpdate(siteUrl, res.user, res.token, activate = true)
    }

    /** `PUT /user/authn`：返回 PublicKeyCredentialCreationOptions JSON（给 `CreatePublicKeyCredentialRequest`） */
    @Throws(Exception::class)
    suspend fun beginRegister(accountId: String): String {
        val res = registry.client(accountId).call(HttpMethod.Put, "/user/authn", JsonObject.serializer())
        return publicKeyJson(res)
    }

    /**
     * `POST /user/authn`：[responseJson] 为平台返回的注册凭证 JSON 原文；
     * [name] 可含 `{os}`/`{browser}` 占位符，由服务端按 [ua] 替换。
     */
    @Throws(Exception::class)
    suspend fun finishRegister(accountId: String, responseJson: String, name: String, ua: String): Passkey =
        registry.client(accountId).call(
            HttpMethod.Post,
            "/user/authn",
            Passkey.serializer(),
            body = CloudreveJson.encodeToJsonElement(
                PasskeyRegisterRequest.serializer(),
                PasskeyRegisterRequest(response = responseJson, name = name, ua = ua),
            ),
        )

    private fun publicKeyJson(options: JsonObject): String {
        val publicKey = options["publicKey"] as? JsonObject
            ?: throw ApiException(ErrorCode.WEBAUTHN_FAILED, "Missing publicKey in WebAuthn options")
        return CloudreveJson.encodeToString(JsonObject.serializer(), publicKey)
    }
}
