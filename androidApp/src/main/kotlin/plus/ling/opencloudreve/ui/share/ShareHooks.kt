package plus.ling.opencloudreve.ui.share

import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.ui.files.FileHook

/** “文件”Tab 内的新建分享对话框目的地 */
@Serializable
data class ShareCreateRoute(val fileUri: String)

/** 文件菜单“分享”：为单个文件/目录新建分享 */
internal val ShareHook = FileHook { context, files ->
    val file = files.singleOrNull() ?: return@FileHook
    context.navController.navigate(ShareCreateRoute(file.path))
}
