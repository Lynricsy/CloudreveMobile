package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.model.CaptchaImage
import plus.ling.opencloudreve.core.model.SiteApp
import plus.ling.opencloudreve.core.model.SiteBasic
import plus.ling.opencloudreve.core.model.SiteEmojis
import plus.ling.opencloudreve.core.model.SiteExplorer
import plus.ling.opencloudreve.core.model.SiteLogin
import plus.ling.opencloudreve.core.model.SiteSection
import plus.ling.opencloudreve.core.model.SiteThumb

/** 站点接口（`/site/…`，均无需登录） */
class SiteApi(private val client: ApiClient) {

    /** `GET /site/ping`：服务端版本号字符串（如 `4.19.11`） */
    @Throws(Exception::class)
    suspend fun ping(): String = client.get("/site/ping", noAuth = true)

    /** `GET /site/captcha`：普通图形验证码 */
    @Throws(Exception::class)
    suspend fun captcha(): CaptchaImage = client.get("/site/captcha", noAuth = true)

    /** `GET /site/config/:section` 原始 JSON（供缓存）；用伴生对象的 `decode*` 还原 */
    @Throws(Exception::class)
    suspend fun config(section: String): JsonObject =
        client.call(HttpMethod.Get, "/site/config/$section", JsonObject.serializer())

    /** 基础配置；带登录态时 `user` 为当前用户 */
    @Throws(Exception::class)
    suspend fun basicConfig(): SiteBasic = client.get("/site/config/${SiteSection.BASIC}")

    @Throws(Exception::class)
    suspend fun loginConfig(): SiteLogin = client.get("/site/config/${SiteSection.LOGIN}", noAuth = true)

    @Throws(Exception::class)
    suspend fun explorerConfig(): SiteExplorer = client.get("/site/config/${SiteSection.EXPLORER}")

    @Throws(Exception::class)
    suspend fun emojisConfig(): SiteEmojis = client.get("/site/config/${SiteSection.EMOJIS}", noAuth = true)

    @Throws(Exception::class)
    suspend fun appConfig(): SiteApp = client.get("/site/config/${SiteSection.APP}", noAuth = true)

    @Throws(Exception::class)
    suspend fun thumbConfig(): SiteThumb = client.get("/site/config/${SiteSection.THUMB}", noAuth = true)

    companion object {
        /** 把缓存的 section JSON 解码为对应模型 */
        fun decodeBasic(json: JsonObject): SiteBasic = CloudreveJson.decodeFromJsonElement(SiteBasic.serializer(), json)
        fun decodeLogin(json: JsonObject): SiteLogin = CloudreveJson.decodeFromJsonElement(SiteLogin.serializer(), json)
        fun decodeExplorer(json: JsonObject): SiteExplorer =
            CloudreveJson.decodeFromJsonElement(SiteExplorer.serializer(), json)
        fun decodeEmojis(json: JsonObject): SiteEmojis = CloudreveJson.decodeFromJsonElement(SiteEmojis.serializer(), json)
        fun decodeApp(json: JsonObject): SiteApp = CloudreveJson.decodeFromJsonElement(SiteApp.serializer(), json)
        fun decodeThumb(json: JsonObject): SiteThumb = CloudreveJson.decodeFromJsonElement(SiteThumb.serializer(), json)
    }
}
