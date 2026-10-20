package plus.ling.opencloudreve.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/**
 * 下载落地位置。`destPath` 的两种形式：
 * - API 29+：`content://media/...` —— MediaStore `Download/OpenCloudreve/` 下的条目，下载期间 `IS_PENDING=1`
 *   （其它应用不可见），完成后发布；同名由 MediaStore 自动改名为“name (1).ext”。
 * - API 28：应用专属外部目录 `Android/data/<pkg>/files/Download/OpenCloudreve/` 下的绝对路径
 *   （无需存储权限；从传输列表“打开/分享”经 FileProvider 交给其它应用）。
 */
internal object DownloadTargets {
    private const val SUB_DIR = "OpenCloudreve"

    /** 为一次下载创建目标并返回 `destPath` */
    fun create(context: Context, displayName: String): String {
        val name = sanitize(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreEntry(context, name).toString()
        } else {
            createAppFile(context, name).absolutePath
        }
    }

    /** 以读写方式打开目标（续传时定位到已写入的末尾） */
    fun open(context: Context, destPath: String): ParcelFileDescriptor {
        val uri = contentUri(destPath)
        return if (uri != null) {
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw java.io.FileNotFoundException(destPath)
        } else {
            val file = File(destPath)
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            )
        }
    }

    /** 下载完成：MediaStore 条目取消 pending，对其它应用可见 */
    fun publish(context: Context, destPath: String) {
        val uri = contentUri(destPath) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    /** 删除未完成的下载（取消时） */
    fun delete(context: Context, destPath: String) {
        val uri = contentUri(destPath)
        try {
            if (uri != null) context.contentResolver.delete(uri, null, null) else File(destPath).delete()
        } catch (_: Exception) {
            // 条目可能已被系统清理（pending 超过 7 天）或用户删除
        }
    }

    /** 交给其它应用打开/分享的 URI（调用方加 `FLAG_GRANT_READ_URI_PERMISSION`） */
    fun shareUri(context: Context, destPath: String): Uri =
        contentUri(destPath) ?: FileProvider.getUriForFile(context, fileProviderAuthority(context), File(destPath))

    /** 按扩展名推断 MIME（打开方式选择用） */
    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

    private fun contentUri(destPath: String): Uri? =
        if (destPath.startsWith("content://")) Uri.parse(destPath) else null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreEntry(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.insert(collection, values)
            ?: throw java.io.IOException("MediaStore rejected download entry for $name")
    }

    private fun createAppFile(context: Context, name: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        val dir = File(base, SUB_DIR).apply { mkdirs() }
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var candidate = File(dir, name)
        var n = 1
        // 先占位（createNewFile 原子），避免并发下载同名文件互相覆盖
        while (!candidate.createNewFile()) {
            candidate = File(dir, "$stem ($n)$ext")
            n++
        }
        return candidate
    }

    /** 文件名中的路径分隔符与控制字符替换为下划线 */
    private fun sanitize(name: String): String =
        name.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }.joinToString("").ifBlank { "download" }
}
