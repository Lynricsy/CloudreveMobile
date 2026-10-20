package plus.ling.opencloudreve.ui.files

import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.model.FileItem

/**
 * “文件”Tab 内的导航入口（各页面与后续阶段 Hook 共用）。
 * 由 `FilesTab` 实现：列表类页面在左侧窗格的嵌套导航中，文件信息在右侧详情窗格（手机上全屏）。
 */
interface FileNavigator {
    /** 进入目录（也可以是回收站、与我共享、分享内目录、搜索 URI） */
    fun openFolder(uri: String)

    /** 打开文件：已接入预览（[FileFeatures.preview]）时预览，否则显示文件信息 */
    fun openFile(file: FileItem, listingUri: String)

    /** 在详情窗格显示文件信息（传入的是条目本身，内部使用链接目标 `linkedUri`） */
    fun showInfo(file: FileItem)

    /** 在 [baseUri] 目录内搜索 */
    fun openSearch(baseUri: String)

    /** 打开分享链接（`https://<site>/s/<id>[/<pw>]`、`cloudreve://<id>[:pw]@share`、分享 id） */
    fun openShareLink(link: String)

    /** 打开目录选择器；选择结果以 [FolderPickResult] 交回发起页（见 `FolderPickerRoute`） */
    fun pickFolder(request: FolderPickRequest)

    fun back()
}

/** 目录选择用途：决定结果被如何消费 */
enum class FolderPickPurpose { Move, Copy, SaveShare }

/**
 * 目录选择请求。
 * @param sources 操作对象 URI（移动时这些目录本身及其子目录不可选）
 */
@Serializable
data class FolderPickRequest(
    val purpose: FolderPickPurpose,
    val sources: List<String>,
    val initialUri: String,
    val excluded: List<String> = emptyList(),
)

/** 目录选择结果 */
@Serializable
data class FolderPickResult(
    val purpose: FolderPickPurpose,
    val sources: List<String>,
    val destination: String,
)

/** “文件”Tab 首页（位置、固定项、容量） */
@Serializable
data object FilesHomeRoute

/** 文件列表页 */
@Serializable
data class FolderRoute(val uri: String)

/** 搜索页 */
@Serializable
data class FileSearchRoute(val baseUri: String)

/** 目录选择器（全屏对话框目的地；请求序列化为 JSON） */
@Serializable
data class FolderPickerRoute(val request: String)

/** 分享浏览页 */
@Serializable
data class ShareVisitRoute(val link: String)

/** 目录选择结果在发起页 `SavedStateHandle` 中的键 */
const val FOLDER_PICK_RESULT_KEY = "folder_pick_result"
