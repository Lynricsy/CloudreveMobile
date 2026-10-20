package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import plus.ling.opencloudreve.core.auth.model.PasskeyRegisterRequest
import plus.ling.opencloudreve.core.model.Capacity
import plus.ling.opencloudreve.core.model.Passkey
import plus.ling.opencloudreve.core.model.PatchUserSettingRequest
import plus.ling.opencloudreve.core.model.RegisterRequest
import plus.ling.opencloudreve.core.model.ResetPasswordEmailRequest
import plus.ling.opencloudreve.core.model.ResetPasswordRequest
import plus.ling.opencloudreve.core.model.ShareListResponse
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.model.UserSettings

/** 用户接口（`/user/…`） */
class UserApi(private val client: ApiClient) {

    /** `GET /user/me` */
    @Throws(Exception::class)
    suspend fun me(): User = client.get("/user/me")

    /** `GET /user/capacity` */
    @Throws(Exception::class)
    suspend fun capacity(): Capacity = client.get("/user/capacity")

    /** `GET /user/setting` */
    @Throws(Exception::class)
    suspend fun settings(): UserSettings = client.get("/user/setting")

    /** `PATCH /user/setting`：只发送非 null 字段 */
    @Throws(Exception::class)
    suspend fun patchSettings(request: PatchUserSettingRequest) {
        client.patch<Unit, PatchUserSettingRequest>("/user/setting", request)
    }

    /** `GET /user/setting/policies`：可用存储策略，默认策略在首位 */
    @Throws(Exception::class)
    suspend fun policies(): List<StoragePolicy> = client.get("/user/setting/policies")

    /**
     * `PUT /user/setting/avatar`：请求体为原始图片字节。
     * [contentType] 为 `image/jpeg`、`image/gif` 时按对应格式解析，其它按 png。超限 40049。
     */
    @Throws(Exception::class)
    suspend fun uploadAvatar(bytes: ByteArray, contentType: String) {
        client.callBytes(HttpMethod.Put, "/user/setting/avatar", Unit.serializer(), bytes, contentType)
    }

    /** 空请求体的 `PUT /user/setting/avatar`：改用 Gravatar */
    @Throws(Exception::class)
    suspend fun useGravatar() {
        client.callBytes(HttpMethod.Put, "/user/setting/avatar", Unit.serializer(), ByteArray(0), "image/png")
    }

    /** 头像图片地址（非 JSON，无需鉴权；用户无头像时 404） */
    fun avatarUrl(userId: String, noCache: Boolean = false): String =
        "${client.apiBase}/user/avatar/$userId" + if (noCache) "?nocache=true" else ""

    /** `GET /user/setting/2fa`：生成待启用的 TOTP secret（base32）；随后用 [patchSettings] 提交 `two_fa_enabled` + `two_fa_code` */
    @Throws(Exception::class)
    suspend fun init2fa(): String = client.get("/user/setting/2fa")

    /** `PUT /user/authn`：开始注册 Passkey，返回 `{publicKey: ...}` 创建选项 */
    @Throws(Exception::class)
    suspend fun passkeyRegisterBegin(): JsonObject =
        client.call(HttpMethod.Put, "/user/authn", JsonObject.serializer())

    /** `POST /user/authn`：提交平台返回的注册凭证 JSON 字符串 */
    @Throws(Exception::class)
    suspend fun passkeyRegisterFinish(response: String, name: String, ua: String): Passkey =
        client.post("/user/authn", PasskeyRegisterRequest(response, name, ua))

    /** `DELETE /user/authn?id=`（[id] 为 [Passkey.id]） */
    @Throws(Exception::class)
    suspend fun deletePasskey(id: String) {
        client.deleteNoBody<Unit>("/user/authn", mapOf("id" to id))
    }

    /**
     * `POST /user` 注册。无需激活时返回新用户（需再登录）；
     * 需要邮件激活时抛 [ApiException]（`code == 203`，无 data）。
     */
    @Throws(Exception::class)
    suspend fun register(email: String, password: String, language: String?, captcha: CaptchaParams?): User =
        client.post("/user", RegisterRequest(email, password, language, captcha?.captcha, captcha?.ticket), noAuth = true)

    /** `POST /user/reset`：发送重置密码邮件 */
    @Throws(Exception::class)
    suspend fun sendResetEmail(email: String, language: String?, captcha: CaptchaParams?) {
        client.post<Unit, ResetPasswordEmailRequest>(
            "/user/reset",
            ResetPasswordEmailRequest(email, language, captcha?.captcha, captcha?.ticket),
            noAuth = true,
        )
    }

    /** `PATCH /user/reset/:id`：用邮件中的 secret 设置新密码（secret 失效 40030） */
    @Throws(Exception::class)
    suspend fun resetPassword(userId: String, secret: String, password: String): User =
        client.patch("/user/reset/$userId", ResetPasswordRequest(password, secret), noAuth = true)

    /** `GET /user/search?keyword=`（至少 2 个字符，最多返回 10 条脱敏用户） */
    @Throws(Exception::class)
    suspend fun search(keyword: String): List<User> =
        client.call(HttpMethod.Get, "/user/search", ListSerializer(User.serializer()), mapOf("keyword" to keyword))

    /** `GET /user/info/:id`：他人公开信息 */
    @Throws(Exception::class)
    suspend fun info(userId: String): User = client.get("/user/info/$userId")

    /** `GET /user/shares/:id`：某用户公开的分享（受其 share_links_in_profile 控制）；[pageSize] 10–100 */
    @Throws(Exception::class)
    suspend fun shares(
        userId: String,
        pageSize: Int = 50,
        nextPageToken: String? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
    ): ShareListResponse = client.get(
        "/user/shares/$userId",
        mapOf(
            "page_size" to pageSize,
            "next_page_token" to nextPageToken,
            "order_by" to orderBy,
            "order_direction" to orderDirection,
        ),
    )
}
