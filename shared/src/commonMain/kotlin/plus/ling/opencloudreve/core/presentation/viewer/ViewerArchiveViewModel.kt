package plus.ling.opencloudreve.core.presentation.viewer

import plus.ling.opencloudreve.core.model.ArchivedFile
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import kotlin.time.Instant

/** 压缩包内条目（已补齐缺失父目录） */
data class ArchiveEntry(
    /** 完整路径（无前导 `/`），如 `a/b/c.txt` */
    val path: String,
    val size: Long,
    val updatedAt: Instant?,
    val isDirectory: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')

    /** 所在目录路径（根为空串） */
    val parentPath: String get() = path.substringBeforeLast('/', "")
}

data class ViewerArchiveState(
    val file: FileItem,
    /** 全部条目：目录在前、文件在后，各自按路径排序 */
    val entries: List<ArchiveEntry> = emptyList(),
    /** 当前浏览的目录（根为空串） */
    val currentPath: String = "",
    /** 文件名编码（[ViewerArchiveViewModel.ENCODINGS] 之一）；null 为服务端默认（UTF-8） */
    val encoding: String? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
    /** 选中的条目路径（解压部分文件） */
    val selection: Set<String> = emptySet(),
    /** 用户组有 archive_task 权限且扩展名可解压 */
    val canExtract: Boolean = false,
) {
    /** 当前目录的直接子项 */
    val children: List<ArchiveEntry> get() = entries.filter { it.parentPath == currentPath }

    /** 面包屑（路径段） */
    val breadcrumbs: List<String> get() = currentPath.split('/').filter { it.isNotEmpty() }
    val canGoUp: Boolean get() = currentPath.isNotEmpty()
    val encodingOptions: List<String> get() = ViewerArchiveViewModel.ENCODINGS
}

sealed interface ViewerArchiveIntent {
    /** 切换文件名编码并重新列出；null 恢复默认 */
    data class SetEncoding(val encoding: String?) : ViewerArchiveIntent
    data class Open(val path: String) : ViewerArchiveIntent
    data object Up : ViewerArchiveIntent
    data object Refresh : ViewerArchiveIntent
    data class ToggleSelection(val path: String) : ViewerArchiveIntent
    data object ClearSelection : ViewerArchiveIntent

    /** 解压全部（[ViewerArchiveEffect.ExtractRequested]，mask 为空） */
    data object ExtractAll : ViewerArchiveIntent

    /** 只解压选中的条目 */
    data object ExtractSelected : ViewerArchiveIntent
}

sealed interface ViewerArchiveEffect {
    /** 平台打开解压任务表单（选择目标目录、密码），带上当前编码与要解压的条目（file_mask） */
    data class ExtractRequested(val file: FileItem, val encoding: String?, val mask: List<String>) : ViewerArchiveEffect
}

/** 压缩包预览（`GET /file/archive`，仅 zip/7z，需要 archive_task 权限） */
class ViewerArchiveViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerArchiveState, ViewerArchiveIntent, ViewerArchiveEffect>(ViewerArchiveState(params.file)) {

    init {
        load()
        launchCatching(onError = {}) {
            val me = env.userSettings.me()
            val allowed = me.hasPermission(GroupPermission.ARCHIVE_TASK) && params.file.extension in EXTRACTABLE_EXTS
            updateState { it.copy(canExtract = allowed) }
        }
    }

    override fun on(intent: ViewerArchiveIntent) {
        when (intent) {
            is ViewerArchiveIntent.SetEncoding -> {
                updateState { it.copy(encoding = intent.encoding?.takeIf { e -> e.isNotBlank() }) }
                load()
            }
            is ViewerArchiveIntent.Open -> {
                val entry = currentState.entries.firstOrNull { it.path == intent.path }
                if (entry?.isDirectory == true || intent.path.isEmpty()) updateState { it.copy(currentPath = intent.path) }
            }
            ViewerArchiveIntent.Up -> updateState { it.copy(currentPath = it.currentPath.substringBeforeLast('/', "")) }
            ViewerArchiveIntent.Refresh -> load()
            is ViewerArchiveIntent.ToggleSelection -> updateState { s ->
                s.copy(selection = if (intent.path in s.selection) s.selection - intent.path else s.selection + intent.path)
            }
            ViewerArchiveIntent.ClearSelection -> updateState { it.copy(selection = emptySet()) }
            ViewerArchiveIntent.ExtractAll -> extract(emptyList())
            ViewerArchiveIntent.ExtractSelected -> currentState.selection.takeIf { it.isNotEmpty() }?.let { extract(it.sorted()) }
        }
    }

    private fun extract(mask: List<String>) {
        if (!currentState.canExtract) return
        sendEffect(ViewerArchiveEffect.ExtractRequested(params.file, currentState.encoding, mask))
    }

    private fun load() {
        val encoding = currentState.encoding
        updateState { it.copy(loading = true, error = null, entries = emptyList(), currentPath = "", selection = emptySet()) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val res = env.files.archiveList(params.file.linkedUri, params.version, encoding)
            updateState { it.copy(loading = false, entries = buildTree(res.files)) }
        }
    }

    companion object {
        /** 服务端支持的文件名编码（前端 `EncodingSelector`） */
        val ENCODINGS = listOf(
            "ibm866", "iso8859_2", "iso8859_3", "iso8859_4", "iso8859_5", "iso8859_6", "iso8859_7", "iso8859_8",
            "iso8859_8I", "iso8859_10", "iso8859_13", "iso8859_14", "iso8859_15", "iso8859_16", "koi8r", "koi8u",
            "macintosh", "windows874", "windows1250", "windows1251", "windows1252", "windows1253", "windows1254",
            "windows1255", "windows1256", "windows1257", "windows1258", "macintoshcyrillic", "gbk", "gb18030", "big5",
            "eucjp", "iso2022jp", "shiftjis", "euckr", "utf16be", "utf16le",
        )

        /** 可创建解压任务的扩展名（前端 `useActionDisplayOpt`） */
        val EXTRACTABLE_EXTS = setOf("zip", "gz", "xz", "tar", "rar", "7z", "bz2")

        /**
         * 前端 `ArchivePreview` 的补齐规则：去掉前导 `/` 与尾部 `/`；为每个文件补齐缺失的各级父目录；
         * 目录在前、文件在后，各自按路径排序；重复路径只保留一条。
         */
        fun buildTree(files: List<ArchivedFile>): List<ArchiveEntry> {
            val byPath = LinkedHashMap<String, ArchiveEntry>()
            files.filter { it.isDirectory }.forEach { f ->
                val p = normalize(f.name)
                if (p.isNotEmpty()) byPath.getOrPut(p) { ArchiveEntry(p, f.size, f.updatedAt, true) }
            }
            files.filterNot { it.isDirectory }.forEach { f ->
                val p = normalize(f.name)
                if (p.isEmpty()) return@forEach
                byPath[p] = ArchiveEntry(p, f.size, f.updatedAt, false)
                val segments = p.split('/')
                for (i in 1 until segments.size) {
                    val dir = segments.subList(0, i).joinToString("/")
                    byPath.getOrPut(dir) { ArchiveEntry(dir, 0, null, true) }
                }
            }
            return byPath.values.sortedWith(compareBy<ArchiveEntry> { !it.isDirectory }.thenBy { it.path })
        }

        private fun normalize(name: String): String = name.trimStart('/').trimEnd('/')
    }
}
