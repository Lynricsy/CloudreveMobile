package plus.ling.opencloudreve.core.files

import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.NewFileTemplate
import plus.ling.opencloudreve.core.model.SiteExplorer
import plus.ling.opencloudreve.core.model.Viewer

/** 打开文件时的预览器选择结果 */
sealed interface ViewerResolution {
    /** 直接用该预览器打开 */
    data class Open(val viewer: Viewer) : ViewerResolution

    /** 多个候选且无默认映射：让用户选择 */
    data class Choose(val candidates: List<Viewer>) : ViewerResolution

    /** 没有可用预览器：平台走系统打开/分享 */
    data object NoViewer : ViewerResolution
}

/** 新建文件菜单项：模板来自某个预览器 */
data class NewFileOption(val viewer: Viewer, val template: NewFileTemplate)

/**
 * 预览器候选计算（照前端 `siteConfigSlice.ts` 与 `thunks/viewer.ts`）。
 *
 * 过滤：跳过 `disabled`；组缺少任一 `required_group_permission` 位则跳过；
 * `platform` 既不是 `all`（或缺失）也不是 [platform]（移动端为 `mobile`）则跳过。
 *
 * 打开顺序：用户偏好 → 该扩展名恰好一个候选 → `default_viewer_mapping[ext]`（须在候选内）→ 让用户选择。
 */
class ViewerResolver(
    explorer: SiteExplorer,
    groupPermission: BoolSet?,
    private val platform: String = PLATFORM_MOBILE,
) {
    /** 过滤后的全部预览器（保持站点配置顺序） */
    val viewers: List<Viewer> = explorer.fileViewers
        .flatMap { it.viewers }
        .filter { v ->
            !v.disabled &&
                v.requiredGroupPermission.all { bit -> groupPermission?.has(bit) == true } &&
                (v.platform.isNullOrEmpty() || v.platform == PLATFORM_ALL || v.platform == platform)
        }

    private val byId: Map<String, Viewer> = viewers.associateBy { it.id }
    private val byExt: Map<String, List<Viewer>> = buildMap<String, MutableList<Viewer>> {
        viewers.forEach { v -> v.exts.forEach { ext -> getOrPut(ext.lowercase()) { mutableListOf() }.add(v) } }
    }
    private val defaultMapping: Map<String, String> = explorer.defaultViewerMapping.mapKeys { it.key.lowercase() }

    fun viewer(id: String): Viewer? = byId[id]

    /** 扩展名（不含点，大小写不敏感）对应的候选 */
    fun candidates(ext: String): List<Viewer> = byExt[ext.lowercase()].orEmpty()

    /** 文件的候选（目录没有） */
    fun candidatesFor(file: FileItem): List<Viewer> = if (file.isFolder) emptyList() else candidates(file.extension)

    /** 选择预览器；[preferredViewerId] 为用户为该扩展名记住的选择 */
    fun resolve(ext: String, preferredViewerId: String? = null): ViewerResolution {
        val list = candidates(ext)
        if (list.isEmpty()) return ViewerResolution.NoViewer
        preferredViewerId?.let { id -> list.firstOrNull { it.id == id }?.let { return ViewerResolution.Open(it) } }
        if (list.size == 1) return ViewerResolution.Open(list.first())
        defaultMapping[ext.lowercase()]?.let { id -> list.firstOrNull { it.id == id }?.let { return ViewerResolution.Open(it) } }
        return ViewerResolution.Choose(list)
    }

    fun resolve(file: FileItem, preferredViewerId: String? = null): ViewerResolution =
        if (file.isFolder) ViewerResolution.NoViewer else resolve(file.extension, preferredViewerId)

    /** “新建文件”菜单：所有可用预览器的模板（按扩展名去重，先出现者优先） */
    fun newFileOptions(): List<NewFileOption> {
        val seen = HashSet<String>()
        return viewers.flatMap { v -> v.templates.map { NewFileOption(v, it) } }
            .filter { seen.add(it.template.ext.lowercase()) }
    }

    companion object {
        const val PLATFORM_MOBILE = "mobile"
        const val PLATFORM_ALL = "all"
        const val PLATFORM_PC = "pc"
    }
}
