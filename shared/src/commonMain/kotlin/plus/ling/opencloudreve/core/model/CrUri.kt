package plus.ling.opencloudreve.core.model

/**
 * `cloudreve://` URI（`pkg/filemanager/fs/uri.go`、前端 `util/uri.ts`）。
 *
 * 形如：
 * - `cloudreve://my/a/b`、`cloudreve://<uid>@my/...`（管理员访问他人文件）
 * - `cloudreve://<shareId>[:<password>]@share/...`
 * - `cloudreve://trash`、`cloudreve://shared_with_me`
 * - 搜索：在 query 中附加 `name`、`type`、`meta_*` 等参数（见 [SearchParams]）
 *
 * 不可变；[path] 为解码后的路径（以 `/` 开头，根为 `/`），[toString] 按服务端/前端规则编码：
 * 路径段与 userinfo 用 `encodeURIComponent` 规则，query 用 `application/x-www-form-urlencoded`（与浏览器 `URLSearchParams` 一致）。
 */
class CrUri private constructor(
    /** 文件系统：[FS_MY]、[FS_SHARE]、[FS_TRASH]、[FS_SHARED_WITH_ME]（小写） */
    val fs: String,
    /** `<uid>@my` 的用户 hashid，或分享 id；没有时为 null */
    val id: String?,
    /** 分享密码；没有时为 null */
    val password: String?,
    /** 解码后的路径段（不含空段） */
    val segments: List<String>,
    /** 原样保留顺序的 query 参数（已解码） */
    private val query: List<Pair<String, String>>,
) {
    /** 解码后的路径，以 `/` 开头；根为 `/` */
    val path: String get() = "/" + segments.joinToString("/")

    /** 是否为文件系统根（忽略 query） */
    fun isRoot(): Boolean = segments.isEmpty()

    /** 最后一段名称；根返回空串 */
    fun name(): String = segments.lastOrNull() ?: ""

    /** 进入子路径（每个参数是一个名称，按段编码；名称内的 `/` 会被编码，不会拆段）。结果不带搜索参数 */
    fun join(vararg names: String): CrUri = joinAll(names.asList())

    /** 同 [join]，便于 Swift 传数组 */
    fun joinAll(names: List<String>): CrUri =
        CrUri(fs, id, password, segments + names.filter { it.isNotEmpty() }, emptyList())

    /** 上级目录；根返回 null。结果不带搜索参数 */
    fun parent(): CrUri? =
        if (segments.isEmpty()) null else CrUri(fs, id, password, segments.dropLast(1), emptyList())

    /** 同文件系统、同 userinfo 的根 */
    fun root(): CrUri = CrUri(fs, id, password, emptyList(), emptyList())

    /** 去掉全部 query（搜索参数） */
    fun withoutSearch(): CrUri = if (query.isEmpty()) this else CrUri(fs, id, password, segments, emptyList())

    /** 替换分享密码（仅对 share 有意义） */
    fun withPassword(password: String?): CrUri =
        CrUri(fs, id, password?.takeIf { it.isNotEmpty() }, segments, query)

    /** 指定 query 键的全部值 */
    fun query(key: String): List<String> = query.filter { it.first == key }.map { it.second }

    /** 全部 query 键（去重、保持顺序） */
    fun queryKeys(): List<String> = query.map { it.first }.distinct()

    /**
     * 以 [params] 替换全部 query。回收站中的名称搜索按前端规则转换为
     * `meta_sys:restore_uri=<encodeURI(names.join(" "))>`（`util/uri.ts:setSearchParam`）。
     */
    fun withSearch(params: SearchParams): CrUri = CrUri(fs, id, password, segments, params.toQuery(fs))

    /** 按后端规则判断是否构成搜索（`uri.go:SearchParameters` 的 `withSearch`） */
    fun isSearch(): Boolean {
        val keys = query.map { it.first }.toSet()
        if (Q_NAME in keys) return true
        if (query(Q_CATEGORY).firstOrNull()?.isNotEmpty() == true) return true
        if (Q_TYPE in keys) return true
        if (keys.any { it.startsWith(Q_META_PREFIX) || it.startsWith(Q_EXACT_META_PREFIX) }) return true
        return listOf(Q_SIZE_GTE, Q_SIZE_LTE, Q_CREATED_GTE, Q_CREATED_LTE, Q_UPDATED_GTE, Q_UPDATED_LTE)
            .any { k -> query(k).firstOrNull()?.toLongOrNull() != null }
    }

    /** 解析出搜索参数；不构成搜索（[isSearch] 为 false）时返回 null */
    fun searchParams(): SearchParams? {
        if (!isSearch()) return null
        val metadata = LinkedHashMap<String, String>()
        val exact = LinkedHashMap<String, String>()
        for ((k, v) in query) {
            // 注意 `exact_meta_` 不以 `meta_` 开头，二者互不干扰
            if (k.startsWith(Q_META_PREFIX)) metadata.getOrPut(k.removePrefix(Q_META_PREFIX)) { v }
            else if (k.startsWith(Q_EXACT_META_PREFIX)) exact.getOrPut(k.removePrefix(Q_EXACT_META_PREFIX)) { v }
        }
        val keys = query.map { it.first }.toSet()
        return SearchParams(
            names = query(Q_NAME),
            nameOpOr = Q_NAME_OP_OR in keys || Q_USE_OR in keys,
            caseFolding = Q_CASE_FOLDING in keys,
            category = query(Q_CATEGORY).firstOrNull()?.takeIf { it.isNotEmpty() },
            type = query(Q_TYPE).firstOrNull()?.let { SearchFileType.fromWire(it) },
            metadata = metadata,
            exactMetadata = exact,
            sizeGte = query(Q_SIZE_GTE).firstOrNull()?.toLongOrNull(),
            sizeLte = query(Q_SIZE_LTE).firstOrNull()?.toLongOrNull(),
            createdGte = query(Q_CREATED_GTE).firstOrNull()?.toLongOrNull(),
            createdLte = query(Q_CREATED_LTE).firstOrNull()?.toLongOrNull(),
            updatedGte = query(Q_UPDATED_GTE).firstOrNull()?.toLongOrNull(),
            updatedLte = query(Q_UPDATED_LTE).firstOrNull()?.toLongOrNull(),
        )
    }

    override fun toString(): String = buildString {
        append(SCHEME_PREFIX)
        if (id != null) {
            append(UriCodec.encodeComponent(id))
            if (password != null) {
                append(':')
                append(UriCodec.encodeComponent(password))
            }
            append('@')
        }
        append(fs)
        for (s in segments) {
            append('/')
            append(UriCodec.encodeComponent(s))
        }
        if (query.isNotEmpty()) {
            append('?')
            append(query.joinToString("&") { (k, v) -> UriCodec.encodeForm(k) + "=" + UriCodec.encodeForm(v) })
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CrUri && fs == other.fs && id == other.id && password == other.password &&
            segments == other.segments && query == other.query

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        const val SCHEME_PREFIX = "cloudreve://"
        const val FS_MY = "my"
        const val FS_SHARE = "share"
        const val FS_TRASH = "trash"
        const val FS_SHARED_WITH_ME = "shared_with_me"

        internal const val Q_NAME = "name"
        internal const val Q_NAME_OP_OR = "name_op_or"
        internal const val Q_USE_OR = "use_or"
        internal const val Q_META_PREFIX = "meta_"
        internal const val Q_EXACT_META_PREFIX = "exact_meta_"
        internal const val Q_CASE_FOLDING = "case_folding"
        internal const val Q_TYPE = "type"
        internal const val Q_CATEGORY = "category"
        internal const val Q_SIZE_GTE = "size_gte"
        internal const val Q_SIZE_LTE = "size_lte"
        internal const val Q_CREATED_GTE = "created_gte"
        internal const val Q_CREATED_LTE = "created_lte"
        internal const val Q_UPDATED_GTE = "updated_gte"
        internal const val Q_UPDATED_LTE = "updated_lte"

        /** 回收站原路径元数据 key */
        const val META_RESTORE_URI = "sys:restore_uri"

        /** 当前用户（[uid] 为空）或指定用户的“我的文件”根 */
        fun my(uid: String? = null): CrUri = CrUri(FS_MY, uid?.takeIf { it.isNotEmpty() }, null, emptyList(), emptyList())

        fun trash(): CrUri = CrUri(FS_TRASH, null, null, emptyList(), emptyList())

        fun sharedWithMe(): CrUri = CrUri(FS_SHARED_WITH_ME, null, null, emptyList(), emptyList())

        /** 分享根 `cloudreve://<id>[:<password>]@share` */
        fun share(id: String, password: String? = null): CrUri =
            CrUri(FS_SHARE, id, password?.takeIf { it.isNotEmpty() }, emptyList(), emptyList())

        /** 解析失败返回 null */
        fun parseOrNull(s: String): CrUri? = runCatching { parse(s) }.getOrNull()

        /** 解析 `cloudreve://` URI；格式错误抛 [IllegalArgumentException] */
        fun parse(s: String): CrUri {
            require(s.startsWith(SCHEME_PREFIX, ignoreCase = true)) { "Invalid cloudreve uri: $s" }
            var rest = s.substring(SCHEME_PREFIX.length).substringBefore('#')
            val rawQuery = rest.substringAfter('?', "")
            rest = rest.substringBefore('?')
            val authority = rest.substringBefore('/')
            val rawPath = if ('/' in rest) rest.substring(rest.indexOf('/')) else ""

            val at = authority.lastIndexOf('@')
            val host = if (at >= 0) authority.substring(at + 1) else authority
            require(host.isNotEmpty()) { "Missing file system: $s" }
            var id: String? = null
            var password: String? = null
            if (at >= 0) {
                val userinfo = authority.substring(0, at)
                val colon = userinfo.indexOf(':')
                if (colon >= 0) {
                    id = UriCodec.decodeComponent(userinfo.substring(0, colon))
                    password = UriCodec.decodeComponent(userinfo.substring(colon + 1)).takeIf { it.isNotEmpty() }
                } else {
                    id = UriCodec.decodeComponent(userinfo)
                }
                id = id.takeIf { it.isNotEmpty() }
            }

            // 与 Go `path.Clean` 一致：去掉空段与 `.`，`..` 回退
            val segments = ArrayList<String>()
            rawPath.split('/').forEach { raw ->
                if (raw.isEmpty()) return@forEach
                when (val seg = UriCodec.decodeComponent(raw)) {
                    "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments += seg
                }
            }

            val query = if (rawQuery.isEmpty()) emptyList() else rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .map { pair ->
                    val eq = pair.indexOf('=')
                    if (eq < 0) UriCodec.decodeForm(pair) to ""
                    else UriCodec.decodeForm(pair.substring(0, eq)) to UriCodec.decodeForm(pair.substring(eq + 1))
                }

            return CrUri(host.lowercase(), id, password, segments, query)
        }
    }
}

/** 搜索类型过滤 */
enum class SearchFileType(val wire: String) {
    FILE("file"),
    FOLDER("folder"),
    ;

    companion object {
        /** 后端：非 `folder` 的值都不是文件夹；这里按前端规则只区分 file 与其它 */
        fun fromWire(v: String): SearchFileType = if (v == "file") FILE else FOLDER
    }
}

/**
 * 搜索参数（附加在 `cloudreve://` URI 的 query 上，见 `uri.go:SearchParameters`）。
 * 时间为 Unix 秒；大小为字节。
 */
data class SearchParams(
    /** 名称匹配，可含 `*` 通配；多个值 */
    val names: List<String> = emptyList(),
    /** 多个名称之间用 OR */
    val nameOpOr: Boolean = false,
    /** 忽略大小写 */
    val caseFolding: Boolean = false,
    /** `image` / `video` / `audio` / `document`（服务端用预设覆盖整个搜索条件） */
    val category: String? = null,
    val type: SearchFileType? = null,
    /** 元数据模糊匹配：key（如 `tag:foo`）→ 值 */
    val metadata: Map<String, String> = emptyMap(),
    /** 元数据精确匹配 */
    val exactMetadata: Map<String, String> = emptyMap(),
    val sizeGte: Long? = null,
    val sizeLte: Long? = null,
    val createdGte: Long? = null,
    val createdLte: Long? = null,
    val updatedGte: Long? = null,
    val updatedLte: Long? = null,
) {
    /** 生成 query（顺序与前端 `setSearchParam` 一致） */
    internal fun toQuery(fs: String): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (names.isNotEmpty()) {
            if (fs == CrUri.FS_TRASH) {
                q += CrUri.Q_META_PREFIX + CrUri.META_RESTORE_URI to UriCodec.encodeUri(names.joinToString(" "))
            } else {
                names.forEach { q += CrUri.Q_NAME to it }
            }
        }
        if (nameOpOr) q += CrUri.Q_NAME_OP_OR to ""
        if (caseFolding) q += CrUri.Q_CASE_FOLDING to ""
        if (!category.isNullOrEmpty()) q += CrUri.Q_CATEGORY to category
        if (type != null) q += CrUri.Q_TYPE to type.wire
        metadata.forEach { (k, v) -> q += CrUri.Q_META_PREFIX + k to v }
        exactMetadata.forEach { (k, v) -> q += CrUri.Q_EXACT_META_PREFIX + k to v }
        sizeGte?.let { q += CrUri.Q_SIZE_GTE to it.toString() }
        sizeLte?.let { q += CrUri.Q_SIZE_LTE to it.toString() }
        createdGte?.let { q += CrUri.Q_CREATED_GTE to it.toString() }
        createdLte?.let { q += CrUri.Q_CREATED_LTE to it.toString() }
        updatedGte?.let { q += CrUri.Q_UPDATED_GTE to it.toString() }
        updatedLte?.let { q += CrUri.Q_UPDATED_LTE to it.toString() }
        return q
    }
}

/** 与 JS `encodeURIComponent`/`encodeURI`/`URLSearchParams` 等价的编解码（UTF-8） */
internal object UriCodec {
    private const val HEX = "0123456789ABCDEF"
    private const val COMPONENT_SAFE = "-_.!~*'()"
    private const val URI_SAFE = "-_.!~*'();,/?:@&=+$#"
    private const val FORM_SAFE = "*-._"

    private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun encode(s: String, safe: String, spaceAsPlus: Boolean): String {
        val sb = StringBuilder(s.length)
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                b >= 0 && (isAlnum(c) || c in safe) -> sb.append(c)
                spaceAsPlus && c == ' ' -> sb.append('+')
                else -> {
                    val v = b.toInt() and 0xFF
                    sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                }
            }
        }
        return sb.toString()
    }

    /** JS `encodeURIComponent` */
    fun encodeComponent(s: String): String = encode(s, COMPONENT_SAFE, false)

    /** JS `encodeURI` */
    fun encodeUri(s: String): String = encode(s, URI_SAFE, false)

    /** `application/x-www-form-urlencoded`（浏览器 `URLSearchParams.toString()`） */
    fun encodeForm(s: String): String = encode(s, FORM_SAFE, true)

    private fun decode(s: String, plusAsSpace: Boolean): String {
        if ('%' !in s && !(plusAsSpace && '+' in s)) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out += ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            if (plusAsSpace && c == '+') {
                out += ' '.code.toByte()
                i++
            } else {
                // 代理对需要两个 char 一起编码为 UTF-8
                val len = if (c.isHighSurrogate() && i + 1 < s.length) 2 else 1
                s.substring(i, i + len).encodeToByteArray().forEach { out += it }
                i += len
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** JS `decodeURIComponent`（非法转义原样保留） */
    fun decodeComponent(s: String): String = decode(s, false)

    /** 表单解码（`+` 视为空格） */
    fun decodeForm(s: String): String = decode(s, true)
}
