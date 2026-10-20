package plus.ling.opencloudreve.ui.files

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.ui.transfer.TransferHooks
import plus.ling.opencloudreve.ui.viewer.PreviewHook

/**
 * 调用后续阶段动作时的上下文。
 * @param listingUri 触发动作时所在的目录（或搜索）URI
 * @param navController “文件”Tab 内部导航（可导航到 [fileFeatureDestinations] 注册的页面）
 */
class FileHookContext(
    val accountId: String,
    val listingUri: String,
    val navController: NavHostController,
    val navigator: FileNavigator,
)

/** 作用于一组文件的动作 */
fun interface FileHook {
    fun run(context: FileHookContext, files: List<FileItem>)
}

/** 作用于目录的动作（上传到目录等） */
fun interface FolderHook {
    fun run(context: FileHookContext, folderUri: String)
}

/**
 * 文件浏览器中由后续阶段实现的动作入口（集中接入点）。
 * 为 null 的动作**不出现在任何菜单中**；显示与否仍受 [FileActionPolicy] 的权限/能力规则约束。
 */
data class FileFeatures(
    /** P5：下载到本地（单选/多选） */
    val download: FileHook? = null,
    /** P5：上传文件到目录（新建菜单“上传文件”） */
    val uploadFiles: FolderHook? = null,
    /** P5：上传照片/视频（新建菜单） */
    val uploadMedia: FolderHook? = null,
    /** P6：打开预览（单个文件）；为 null 时点按文件显示文件信息 */
    val preview: FileHook? = null,
    /** P7：新建分享 */
    val share: FileHook? = null,
    /** P7：版本管理 */
    val versions: FileHook? = null,
    /** P7：标签与元数据、图标自定义 */
    val metadata: FileHook? = null,
    /** P7：压缩 */
    val createArchive: FileHook? = null,
    /** P7：解压 */
    val extractArchive: FileHook? = null,
    /** P7：从种子文件创建远程下载 */
    val remoteDownload: FileHook? = null,
    /** P7：转移存储策略 */
    val relocate: FileHook? = null,
)

/** 当前接入的实现：后续阶段在此填入各自的 Hook */
internal val InstalledFileFeatures = FileFeatures(
    download = TransferHooks.download,
    uploadFiles = TransferHooks.uploadFiles,
    uploadMedia = TransferHooks.uploadMedia,
    preview = PreviewHook,
)

val LocalFileFeatures = staticCompositionLocalOf { InstalledFileFeatures }
