package plus.ling.opencloudreve.ui.auth

import java.net.URI
import java.net.URISyntaxException

/**
 * 验证码 WebView 的信任边界：只有站点验证码页本身（与加载地址同 origin、同路径的主 frame）能把验证结果交给原生，
 * 主 frame 也只能停留在站点 origin 内；验证码服务商的 iframe/子资源照常加载，但不能调用原生桥。
 */
internal object CaptchaPagePolicy {
    /** 规范化 origin：`scheme://host[:port]`（小写，省略默认端口）；不是 http(s) 绝对地址时返回 null */
    fun origin(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it != -1 && it != defaultPort }
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** 主 frame 能否导航到 [url]（只允许验证码页 [captchaUrl] 所在站点 origin） */
    fun allowsMainFrame(url: String, captchaUrl: String): Boolean {
        val site = origin(captchaUrl) ?: return false
        return origin(url) == site
    }

    /**
     * 是否接受一条验证结果消息：来自主 frame、消息来源 origin 与当前页面都等于验证码页的 origin，
     * 且当前页面路径（规范化后）就是验证码页路径。
     */
    fun acceptsToken(pageUrl: String?, captchaUrl: String, isMainFrame: Boolean, sourceOrigin: String): Boolean {
        if (!isMainFrame || pageUrl == null) return false
        val site = origin(captchaUrl) ?: return false
        if (origin(sourceOrigin) != site || origin(pageUrl) != site) return false
        val expected = normalizedPath(captchaUrl) ?: return false
        return normalizedPath(pageUrl) == expected
    }

    private fun normalizedPath(url: String): String? = parse(url)?.normalize()?.rawPath?.trimEnd('/')

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: URISyntaxException) {
        null
    }
}
