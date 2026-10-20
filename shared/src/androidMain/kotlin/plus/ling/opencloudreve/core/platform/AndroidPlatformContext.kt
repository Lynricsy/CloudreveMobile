package plus.ling.opencloudreve.core.platform

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.db.DatabaseDriverFactory
import plus.ling.opencloudreve.core.transfer.FileSystemBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

/** Android 平台上下文：数据库在应用私有目录，机密存 EncryptedSharedPreferences，文件经 ContentResolver */
class AndroidPlatformContext(context: Context, override val appVersion: String) : PlatformContext {
    private val appContext = context.applicationContext

    override val databaseDriverFactory = DatabaseDriverFactory(appContext)
    override val secureStore: SecretStore = SecureStore(appContext)
    override val fileSystem: FileSystemBridge = AndroidFileSystemBridge(appContext)
    override val platformName: String = PlatformContext.PLATFORM_ANDROID
}

/**
 * Android 文件桥：`sourceRef` 为 `content://`、`file://` URI 或绝对路径；临时目录为 `cacheDir/transfer`。
 */
class AndroidFileSystemBridge(context: Context) : FileSystemBridge {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    @Throws(Exception::class)
    override suspend fun size(sourceRef: String): Long? = withContext(Dispatchers.IO) {
        try {
            val local = localFile(sourceRef)
            if (local != null) return@withContext if (local.isFile) local.length() else null
            val uri = Uri.parse(sourceRef)
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) return@withContext c.getLong(idx)
            }
            // 部分提供方不填 SIZE：退回文件描述符长度
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0 } ?: afd.parcelFileDescriptor.statSize.takeIf { it >= 0 }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: FileNotFoundException) {
            null
        }
    }

    @Throws(Exception::class)
    override suspend fun readRange(sourceRef: String, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val local = localFile(sourceRef)
            if (local != null) {
                FileInputStream(local).use { return@withContext readAt(it, offset, length) }
            }
            val uri = Uri.parse(sourceRef)
            // 优先可定位的文件描述符；管道类提供方退回顺序跳读
            val pfd = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (_: FileNotFoundException) {
                null
            }
            if (pfd != null && pfd.statSize >= 0) {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { return@withContext readAt(it, offset, length) }
            }
            pfd?.close()
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(sourceRef)
            stream.use {
                skipFully(it, offset)
                readUpTo(it, length)
            }
        }

    override fun tempDir(): String = File(appContext.cacheDir, "transfer").apply { mkdirs() }.absolutePath

    @Throws(Exception::class)
    override suspend fun writeFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    @Throws(Exception::class)
    override suspend fun appendFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val f = File(path)
        f.parentFile?.mkdirs()
        FileOutputStream(f, true).use { it.write(bytes) }
    }

    @Throws(Exception::class)
    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    private fun localFile(ref: String): File? = when {
        ref.startsWith("/") -> File(ref)
        ref.startsWith("file://") -> Uri.parse(ref).path?.let(::File)
        else -> null
    }

    private fun readAt(stream: FileInputStream, offset: Long, length: Int): ByteArray {
        stream.channel.position(offset)
        return readUpTo(stream, length)
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // skip 可能返回 0：读一个字节判断是否到末尾
                if (stream.read() < 0) return
                remaining--
            }
        }
    }

    private fun readUpTo(stream: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = stream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }
}
