package plus.ling.opencloudreve.ui.viewer

import plus.ling.opencloudreve.core.model.CrUri
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * HLS 播放列表/分片地址映射（对应 Web `VideoViewer.tsx` 的 `m3u8UrlTransform` + `getUnmaskedEntityUrl`）。
 *
 * 入口 m3u8 以“掩码地址” `ocr-hls://masked/<云盘路径>` 交给播放器，播放器按 RFC 3986 把播放列表里的相对地址
 * 解析到同一掩码空间（`seg001.ts` → 同目录、`/a.ts` → 文件系统根、`../` 已规范化，子播放列表同理逐级相对）；
 * 加载时再换回云盘 URI，逐个经 `POST /file/url` 签名。外部绝对 http(s) 地址不在掩码空间，原样加载。
 */
internal object HlsMask {
    const val SCHEME = "ocr-hls"
    private const val PREFIX = "$SCHEME://masked"

    /** [linked]（文件的链接目标 URI）对应的掩码入口地址 */
    fun entry(linked: CrUri): String = PREFIX + "/" + linked.segments.joinToString("/") { encode(it) }

    fun isMasked(uri: String): Boolean = uri.startsWith("$PREFIX/")

    /**
     * 掩码地址 → 与 [linked] 同文件系统（同 `<uid>@my`、同分享及密码）的云盘 URI；忽略 query/fragment。
     * 不是掩码地址，或解码后出现 `.`/`..`/含 `/` 的段（编码绕过规范化）时返回 null。
     */
    fun cloudUri(masked: String, linked: CrUri): CrUri? {
        if (!isMasked(masked)) return null
        val path = masked.substring(PREFIX.length).substringBefore('#').substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) ?: return null }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." || '/' in it }) return null
        return linked.root().joinAll(segments)
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun decode(segment: String): String? = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}
