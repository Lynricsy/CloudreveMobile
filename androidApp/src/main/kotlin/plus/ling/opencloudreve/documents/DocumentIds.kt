package plus.ling.opencloudreve.documents

import plus.ling.opencloudreve.core.model.CrUri
import java.io.FileNotFoundException

/**
 * 系统文件集成的文档 ID：`<accountId>|<cloudreve uri>`。
 *
 * accountId 形如 `<uid>@<host[:port][/path]>`，不含 `|`；uri 统一按 [CrUri] 规范化后的字符串，
 * 保证同一对象无论来自列表、新建还是信息接口都得到同一个 ID（系统按 ID 记住授权与最近位置）。
 */
internal data class DocumentId(val accountId: String, val uri: CrUri) {
    val encoded: String get() = "$accountId|$uri"

    val isRoot: Boolean get() = uri.fs == CrUri.FS_MY && uri.id == null && uri.isRoot()

    /** 最后一段名称；根返回空串 */
    val name: String get() = uri.segments.lastOrNull().orEmpty()

    fun parent(): DocumentId? = uri.parent()?.let { DocumentId(accountId, it) }

    fun child(name: String): DocumentId = DocumentId(accountId, uri.join(name))

    /** [other] 是否位于本目录之下（任意深度，不含自身） */
    fun isAncestorOf(other: DocumentId): Boolean =
        accountId == other.accountId &&
            uri.fs == other.uri.fs &&
            uri.id == other.uri.id &&
            other.uri.segments.size > uri.segments.size &&
            other.uri.segments.subList(0, uri.segments.size) == uri.segments

    companion object {
        private const val SEPARATOR = '|'

        fun root(accountId: String): DocumentId = DocumentId(accountId, CrUri.my())

        fun of(accountId: String, uri: String): DocumentId =
            DocumentId(accountId, CrUri.parseOrNull(uri) ?: throw FileNotFoundException("Invalid uri: $uri"))

        /** 解析失败抛 [FileNotFoundException]（系统按“文档不存在”处理） */
        fun parse(documentId: String): DocumentId {
            val i = documentId.indexOf(SEPARATOR)
            if (i <= 0) throw FileNotFoundException("Invalid document id: $documentId")
            return of(documentId.substring(0, i), documentId.substring(i + 1))
        }
    }
}
