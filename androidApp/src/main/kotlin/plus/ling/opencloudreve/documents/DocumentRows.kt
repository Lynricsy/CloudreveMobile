package plus.ling.opencloudreve.documents

import android.database.MatrixCursor
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.NavigatorCapability

/** 文档行的列、MIME 与 `COLUMN_FLAGS` 计算 */
internal object DocumentRows {
    val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
    )

    private const val FALLBACK_MIME = "application/octet-stream"

    fun mimeOf(file: FileItem): String =
        if (file.isFolder) Document.MIME_TYPE_DIR else mimeOfName(file.name)

    fun mimeOfName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return FALLBACK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: FALLBACK_MIME
    }

    /** 能生成缩略图的文件（其余类型不请求 `/file/thumb`，避免对每个文件发无效请求） */
    fun thumbnailCandidate(file: FileItem): Boolean {
        if (file.isFolder || file.thumbDisabled || file.isSymbolic) return false
        val mime = mimeOfName(file.name)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    /**
     * 按能力位计算 flags（规则同文件菜单 `FileActionPolicy`）：
     * 只有“我的文件”中本人拥有的对象可改；能力位优先取文件自身，缺失时用所在目录的 `props.capability`。
     */
    fun flagsOf(file: FileItem, dirCapability: BoolSet?): Int {
        val cap = file.capability ?: dirCapability
        fun has(bit: Int) = cap?.has(bit) == true
        val updatable = file.owned && CrUri.parseOrNull(file.path)?.fs == CrUri.FS_MY
        var flags = 0
        if (file.isFolder) {
            if (updatable && (has(NavigatorCapability.CREATE_FILE) || has(NavigatorCapability.UPLOAD_FILE))) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else {
            if (updatable && !file.isSymbolic && has(NavigatorCapability.UPLOAD_FILE)) {
                flags = flags or Document.FLAG_SUPPORTS_WRITE
            }
            if (thumbnailCandidate(file)) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        if (updatable && has(NavigatorCapability.RENAME_FILE)) flags = flags or Document.FLAG_SUPPORTS_RENAME
        if (updatable && (has(NavigatorCapability.SOFT_DELETE) || has(NavigatorCapability.DELETE_FILE))) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }
        // 服务端只允许在“我的文件”内移动/复制
        if (updatable && cap != null) flags = flags or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY
        return flags
    }

    fun addFile(cursor: MatrixCursor, id: DocumentId, file: FileItem, dirCapability: BoolSet?) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id.encoded)
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { id.name })
            add(Document.COLUMN_LAST_MODIFIED, file.updatedAt?.toEpochMilliseconds())
            add(Document.COLUMN_FLAGS, flagsOf(file, dirCapability))
            // 目录大小未知（列表中目录为 0）
            add(Document.COLUMN_SIZE, if (file.isFolder) null else file.size)
        }
    }

    /** 账户根目录（`cloudreve://my`）；[capability] 来自根目录列表的 `props.capability`，未知时按可写处理 */
    fun addRoot(cursor: MatrixCursor, id: DocumentId, title: String, capability: BoolSet?) {
        val canCreate = capability == null ||
            capability.has(NavigatorCapability.CREATE_FILE) || capability.has(NavigatorCapability.UPLOAD_FILE)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id.encoded)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_DISPLAY_NAME, title)
            add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, if (canCreate) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
            add(Document.COLUMN_SIZE, null)
        }
    }
}
