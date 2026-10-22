package plus.ling.opencloudreve.documents

import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import java.io.FileNotFoundException

/**
 * 系统文件集成的文档 ID（系统按 ID 记住长期授权与最近位置，必须不随改名/移动变化）：
 *
 * - 根：`<accountId>|cloudreve://my`；
 * - 对象：`<accountId>|id:<fileId>`，fileId 为服务端稳定的文件 hashid，当前路径用 `GET /file/info?id=` 解析；
 * - 旧版路径式：`<accountId>|cloudreve://my/...`，此前发放给外部应用的授权仍按路径解析。
 *
 * accountId 形如 `<uid>@<host[:port][/path]>`，不含 `|`。只接受当前用户“我的文件”中的对象
 * （`cloudreve://my`，不带 `<uid>@`），其它文件系统一律视为不存在。
 */
internal sealed class DocumentId {
    abstract val accountId: String
    abstract val encoded: String

    /** 账户根目录 */
    data class Root(override val accountId: String) : DocumentId() {
        override val encoded: String get() = "$accountId$SEPARATOR${CrUri.my()}"
    }

    /** 按服务端文件 ID 标识的对象 */
    data class File(override val accountId: String, val fileId: String) : DocumentId() {
        override val encoded: String get() = "$accountId$SEPARATOR$FILE_PREFIX$fileId"
    }

    /** 旧版路径式 ID（只解析，不再发放） */
    data class Path(override val accountId: String, val uri: CrUri) : DocumentId() {
        override val encoded: String get() = "$accountId$SEPARATOR$uri"
    }

    override fun toString(): String = encoded

    companion object {
        private const val SEPARATOR = '|'
        private const val FILE_PREFIX = "id:"
        private val FILE_ID = Regex("[A-Za-z0-9_-]+")

        fun root(accountId: String): DocumentId = Root(accountId)

        /** 对象的文档 ID：有服务端 ID 时用 ID，根目录用根 ID，缺 ID 的旧数据退回路径 */
        fun of(accountId: String, file: FileItem): DocumentId {
            val uri = ownUri(file.path)
            if (uri.isRoot()) return Root(accountId)
            return if (FILE_ID.matches(file.id)) File(accountId, file.id) else Path(accountId, uri)
        }

        /** 解析失败或不在“我的文件”中抛 [FileNotFoundException]（系统按“文档不存在”处理） */
        fun parse(documentId: String): DocumentId {
            val i = documentId.indexOf(SEPARATOR)
            if (i <= 0) throw FileNotFoundException("Invalid document id: $documentId")
            val accountId = documentId.substring(0, i)
            val rest = documentId.substring(i + 1)
            if (rest.startsWith(FILE_PREFIX)) {
                val fileId = rest.substring(FILE_PREFIX.length)
                if (!FILE_ID.matches(fileId)) throw FileNotFoundException("Invalid document id: $documentId")
                return File(accountId, fileId)
            }
            val uri = ownUri(rest)
            return if (uri.isRoot()) Root(accountId) else Path(accountId, uri)
        }

        /**
         * 解析为当前用户“我的文件”中的规范 URI（[CrUri.parse] 与服务端一致地解码 `%2F`、清理 `..`）；
         * 其它文件系统、他人文件或带搜索参数的 URI 抛 [FileNotFoundException]
         */
        fun ownUri(uri: String): CrUri {
            val parsed = CrUri.parseOrNull(uri) ?: throw FileNotFoundException("Invalid uri: $uri")
            if (parsed.fs != CrUri.FS_MY || parsed.id != null || parsed.queryKeys().isNotEmpty()) {
                throw FileNotFoundException("Not in my files: $uri")
            }
            return parsed
        }

        /** [child] 是否位于 [parent] 之下（任意深度，不含自身）；两者都应是 [ownUri] 规范化后的位置 */
        fun isAncestor(parent: CrUri, child: CrUri): Boolean =
            parent.fs == child.fs &&
                parent.id == child.id &&
                child.segments.size > parent.segments.size &&
                child.segments.subList(0, parent.segments.size) == parent.segments
    }
}
