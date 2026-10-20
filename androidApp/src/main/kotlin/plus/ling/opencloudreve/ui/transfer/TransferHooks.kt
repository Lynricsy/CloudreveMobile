package plus.ling.opencloudreve.ui.transfer

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.transfer.DownloadTargets
import plus.ling.opencloudreve.transfer.TransferRuntime
import plus.ling.opencloudreve.ui.files.FileHook
import plus.ling.opencloudreve.ui.files.FolderHook

/** 文件浏览器的 P5 动作：下载、上传文件、上传照片/视频（接入 `InstalledFileFeatures`） */
internal object TransferHooks {
    /** 下载所选文件（软链接下载其目标）；目录跳过并提示 */
    val download = FileHook { context, files ->
        enqueueDownloads(context.navController.context.applicationContext, context.accountId, files)
    }

    /** 系统文件选择器多选上传 */
    val uploadFiles = FolderHook { context, folderUri ->
        context.navController.navigate(UploadRoute(folderUri, media = false))
    }

    /** 照片选择器 / 拍照上传 */
    val uploadMedia = FolderHook { context, folderUri ->
        context.navController.navigate(UploadRoute(folderUri, media = true))
    }

    private fun enqueueDownloads(context: Context, accountId: String, files: List<FileItem>) {
        val targets = files.filterNot { it.isFolder }
        val skipped = files.size - targets.size
        TransferRuntime.scope.launch {
            val manager = CoreGraph.transferManager()
            var added = 0
            var failed = 0
            for (file in targets) {
                try {
                    val dest = withContext(Dispatchers.IO) { DownloadTargets.create(context, file.name) }
                    manager.enqueueDownload(accountId, file.linkedUri, file.name, file.size, dest)
                    added++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            withContext(Dispatchers.Main) {
                val parts = buildList {
                    if (added > 0) add(context.resources.getQuantityString(R.plurals.transfer_download_added, added, added))
                    if (skipped > 0) add(context.resources.getQuantityString(R.plurals.transfer_download_skipped_folders, skipped, skipped))
                    if (failed > 0) add(context.resources.getQuantityString(R.plurals.transfer_download_failed, failed, failed))
                }
                if (parts.isNotEmpty()) Toast.makeText(context, parts.joinToString("\n"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
