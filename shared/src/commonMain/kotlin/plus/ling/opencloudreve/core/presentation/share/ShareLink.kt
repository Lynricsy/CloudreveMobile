package plus.ling.opencloudreve.core.presentation.share

import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.UriCodec

/**
 * 解析出的分享链接。
 * @property siteUrl 链接所属站点（`https://host[/子路径]`）；`cloudreve://` 或纯 id 为 null
 * @property path 分享内的子路径段（`cloudreve://<id>@share/a/b` 形式才有）
 */
data class ShareLink(
    val id: String,
    val password: String? = null,
    val siteUrl: String? = null,
    val path: List<String> = emptyList(),
) {
    /** 分享根 URI（带密码） */
    val rootUri: String get() = CrUri.share(id, password).toString()

    /** 链接指向的 URI（根或子目录） */
    val uri: String get() = CrUri.share(id, password).joinAll(path).toString()
}

/**
 * 分享链接解析：
 * - `https://<site>/s/<id>[/<pw>]`（取最后一个 `/s/`，与前端拆分规则一致）
 * - `cloudreve://<id>[:<pw>]@share[/path]`
 * - `opencloudreve://open?url=<上述任一形式>`
 * - 纯分享 id（字母数字）
 */
object ShareLinkParser {
    private val idPattern = Regex("^[A-Za-z0-9]+$")

    fun parse(input: String): ShareLink? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return when {
            text.startsWith(CrUri.SCHEME_PREFIX, ignoreCase = true) -> parseCrUri(text)
            text.startsWith(DEEP_LINK_PREFIX, ignoreCase = true) -> {
                val query = text.substringAfter('?', "")
                val url = query.split('&').firstNotNullOfOrNull { pair ->
                    if (pair.substringBefore('=') == "url") UriCodec.decodeForm(pair.substringAfter('=', "")) else null
                }
                url?.let { if (it.startsWith(DEEP_LINK_PREFIX, ignoreCase = true)) null else parse(it) }
            }
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> parseHttp(text)
            idPattern.matches(text) -> ShareLink(text)
            else -> null
        }
    }

    private fun parseCrUri(text: String): ShareLink? {
        val uri = CrUri.parseOrNull(text) ?: return null
        if (uri.fs != CrUri.FS_SHARE) return null
        val id = uri.id ?: return null
        return ShareLink(id, uri.password, null, uri.segments)
    }

    private fun parseHttp(text: String): ShareLink? {
        val clean = text.substringBefore('#').substringBefore('?')
        val idx = clean.lastIndexOf("/s/")
        if (idx < 0) return null
        val schemeEnd = clean.indexOf("://") + 3
        if (idx < schemeEnd) return null
        val site = clean.substring(0, idx).trimEnd('/')
        val parts = clean.substring(idx + 3).split('/').filter { it.isNotEmpty() }.map(UriCodec::decodeComponent)
        val id = parts.getOrNull(0)?.takeIf { idPattern.matches(it) } ?: return null
        return ShareLink(id, parts.getOrNull(1)?.takeIf { it.isNotEmpty() }, site)
    }

    /** 新建/编辑分享返回的链接 → (id, 密码) */
    fun splitCreated(link: String): ShareLink? = parseHttp(link)

    private const val DEEP_LINK_PREFIX = "opencloudreve://"
}
