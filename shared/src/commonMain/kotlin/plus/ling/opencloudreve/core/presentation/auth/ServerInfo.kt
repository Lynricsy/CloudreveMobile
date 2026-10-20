package plus.ling.opencloudreve.core.presentation.auth

import plus.ling.opencloudreve.core.api.endpoints.SiteApi
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.model.SiteBasic

/**
 * 服务器探测结果。
 * [enhanced] 为 true 表示服务端 ≥ [ServerVersion.ENHANCED_MIN]，支持 Passkey/网页登录、会话交接与 WebView 验证码页；
 * 低版本站点仍可密码登录，但这些入口要隐藏或提示“请在网页端登录”。
 */
data class ServerInfo(
    val siteUrl: String,
    val version: String,
    val title: String,
    /** 深色背景用的 Logo（绝对地址，可能是 SVG） */
    val logoUrl: String?,
    /** 浅色背景用的 Logo（绝对地址，可能是 SVG） */
    val logoLightUrl: String?,
    val enhanced: Boolean,
    val basic: SiteBasic,
)

/** 服务端版本比较 */
object ServerVersion {
    /** 原生客户端增强功能（内置移动端 OAuth client、assetlinks、会话交接、/captcha 页）所需的最低版本 */
    const val ENHANCED_MIN = "4.19.11"

    /** `version >= min`；按 `.` 分段逐段比较数字部分（`4.19.11-pro` 视为 4.19.11），无法解析的段按 0 处理 */
    fun isAtLeast(version: String, min: String): Boolean {
        val a = parse(version)
        val b = parse(min)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

/** `GET /site/ping` + `GET /site/config/basic`（匿名） */
internal suspend fun probeServer(registry: ApiClientRegistry, siteUrl: String): ServerInfo {
    val api = SiteApi(registry.anonymous(siteUrl))
    val version = api.ping()
    val basic = api.basicConfig()
    return ServerInfo(
        siteUrl = siteUrl,
        version = version,
        title = basic.title,
        logoUrl = absoluteUrl(siteUrl, basic.logo),
        logoLightUrl = absoluteUrl(siteUrl, basic.logoLight),
        enhanced = ServerVersion.isAtLeast(version, ServerVersion.ENHANCED_MIN),
        basic = basic,
    )
}

/** 站点配置里的相对路径（如 `/static/img/logo.svg`）转为绝对地址 */
internal fun absoluteUrl(siteUrl: String, path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    return siteUrl.trimEnd('/') + "/" + path.trimStart('/')
}
