package plus.ling.opencloudreve.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.UploadSource
import java.io.File
import java.util.UUID

/**
 * 选择器/分享菜单返回的 URI → 上传源（`sourceRef`）。
 *
 * - 能拿到持久读权限（`takePersistableUriPermission`）的 URI 直接引用，进程重启后仍可续传；授权在最后一个引用它的任务结束后释放；
 * - 拿不到的（分享菜单的临时授权、部分相册提供方）先复制到应用私有收件箱 `files/transfer-inbox/<uuid>/`，
 *   上传完成或取消后由执行器删除副本（[release]）。
 */
internal object UploadSources {
    private const val INBOX = "transfer-inbox"
    private const val TAG = "UploadSources"

    fun inboxDir(context: Context): File = File(context.filesDir, INBOX)

    /** 解析一批 URI；[forceCopy] 为 true 时（分享菜单）一律复制。读不到的项跳过 */
    suspend fun fromUris(context: Context, uris: List<Uri>, forceCopy: Boolean = false): List<UploadSource> =
        withContext(Dispatchers.IO) {
            uris.distinct().mapNotNull { uri ->
                try {
                    resolve(context, uri, forceCopy)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Skip unreadable upload source $uri", e)
                    null
                }
            }
        }

    /** 收件箱里的本地文件（拍照结果） */
    fun fromFile(file: File, mimeType: String?): UploadSource =
        UploadSource(file.absolutePath, file.name, file.length(), file.lastModified(), mimeType)

    /** 为拍照结果或系统文件集成写入的内容新建一个收件箱文件（尚不存在） */
    fun newCameraFile(context: Context, name: String): File {
        val dir = File(inboxDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, name)
    }

    /** 删除收件箱里的副本（拍照结果、分享副本、系统文件集成写入的内容）；不在收件箱内的路径忽略 */
    fun discard(context: Context, sourceRef: String) {
        if (!sourceRef.startsWith("/")) return
        try {
            val file = File(sourceRef)
            val inbox = inboxDir(context).canonicalFile
            if (file.canonicalPath.startsWith(inbox.path + File.separator)) {
                file.delete()
                // 删除每个副本独占的 <uuid> 目录
                file.parentFile?.takeIf { it.parentFile?.canonicalFile == inbox }?.delete()
            }
        } catch (_: Exception) {
            // 删除失败不影响任务结果
        }
    }

    /**
     * 上传任务结束（完成/取消/移除）后释放源：收件箱副本删除；持久授权的 URI 只在没有其他任务仍需要时释放
     * （持久授权数量有上限，不能无限累积；见 [SourceGrants]）。
     */
    suspend fun release(context: Context, task: TransferItem) {
        val sourceRef = task.sourceRef ?: return
        if (!sourceRef.startsWith("content://")) {
            discard(context, sourceRef)
            return
        }
        try {
            grants(context).release(sourceRef, task.id, task.createdAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Release grant of $sourceRef failed", e)
        }
    }

    @Volatile private var grants: SourceGrants? = null

    private fun grants(context: Context): SourceGrants = grants ?: synchronized(this) {
        grants ?: SourceGrants(
            references = { ref -> sourceReferences(ref) },
            releaseGrant = { ref ->
                val uri = Uri.parse(ref)
                val resolver = context.applicationContext.contentResolver
                if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            },
        ).also { grants = it }
    }

    /** 全部账户中引用 [sourceRef] 的上传任务 */
    private suspend fun sourceReferences(sourceRef: String): List<SourceGrants.SourceReference> =
        CoreGraph.accounts().accounts.value.flatMap { account ->
            CoreGraph.transferRepository().observeTasks(account.id).first()
                .filter { it.kind == TransferKind.UPLOAD && it.sourceRef == sourceRef }
                .map { SourceGrants.SourceReference(it.id, it.state, it.createdAt) }
        }

    private suspend fun resolve(context: Context, uri: Uri, forceCopy: Boolean): UploadSource? {
        val resolver = context.contentResolver
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (!file.isFile) return null
            return copyToInbox(context, uri, file.name, resolver.getType(uri))
        }
        var name: String? = null
        var size: Long? = null
        var modified: Long? = null
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { modified = c.getLong(it).takeIf { v -> v > 0 } }
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = resolver.getType(uri)
        if (!forceCopy && size != null) {
            // 先认领再取授权：与其它任务结束时的释放判断串行，释放不会撤掉这次刚取得的授权
            grants(context).claim(uri.toString())
            if (persist(context, uri)) return UploadSource(uri.toString(), displayName, size!!, modified, mime)
        }
        return copyToInbox(context, uri, displayName, mime, modified)
    }

    /** 尝试取得持久读权限（`OpenDocument`/照片选择器的 URI 支持） */
    private fun persist(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun copyToInbox(context: Context, uri: Uri, name: String, mime: String?, modified: Long? = null): UploadSource {
        val safeName = name.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }.joinToString("")
        val dir = File(inboxDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, safeName.ifBlank { "file" })
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst, 1 shl 16) } }
        } catch (e: Exception) {
            target.delete()
            dir.delete()
            throw e
        }
        modified?.let { target.setLastModified(it) }
        return UploadSource(target.absolutePath, name, target.length(), modified ?: target.lastModified(), mime)
    }
}
