package plus.ling.opencloudreve.ui.workflow

import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskMode
import plus.ling.opencloudreve.ui.files.FileHook

/** “文件”Tab 内的压缩/解压/转移对话框目的地 */
@Serializable
data class ArchiveTaskRoute(
    /** [ArchiveTaskMode] 名称 */
    val mode: String,
    val srcUris: List<String>,
    val dstUri: String,
    val archiveName: String? = null,
)

/** “文件”Tab 内的新建远程下载对话框目的地（种子文件菜单“离线下载”） */
@Serializable
data class RemoteDownloadRoute(val dstUri: String, val torrentUri: String? = null)

/** 文件浏览器的 P7 后台任务动作（接入 `InstalledFileFeatures`） */
internal object WorkflowHooks {
    /** 压缩到所选文件所在目录；单个文件默认以其名称命名压缩包 */
    val createArchive = FileHook { context, files ->
        val first = files.firstOrNull() ?: return@FileHook
        val name = files.singleOrNull()?.let { f ->
            val base = if (f.isFolder) f.name else f.name.substringBeforeLast('.').ifEmpty { f.name }
            "$base.zip"
        }
        context.navController.navigate(ArchiveTaskRoute(ArchiveTaskMode.COMPRESS.name, files.map { it.linkedUri }, parentOf(first, context.listingUri), name))
    }

    /** 解压到压缩包所在目录（可改） */
    val extractArchive = FileHook { context, files ->
        val file = files.firstOrNull() ?: return@FileHook
        context.navController.navigate(ArchiveTaskRoute(ArchiveTaskMode.EXTRACT.name, listOf(file.linkedUri), parentOf(file, context.listingUri)))
    }

    /** 用种子文件创建远程下载，默认保存到种子所在目录 */
    val remoteDownload = FileHook { context, files ->
        val file = files.firstOrNull() ?: return@FileHook
        context.navController.navigate(RemoteDownloadRoute(parentOf(file, context.listingUri), file.linkedUri))
    }

    /** 转移存储策略 */
    val relocate = FileHook { context, files ->
        if (files.isEmpty()) return@FileHook
        context.navController.navigate(ArchiveTaskRoute(ArchiveTaskMode.RELOCATE.name, files.map { it.linkedUri }, context.listingUri))
    }

    private fun parentOf(file: FileItem, fallback: String): String =
        CrUri.parseOrNull(file.path)?.parent()?.toString() ?: fallback
}
