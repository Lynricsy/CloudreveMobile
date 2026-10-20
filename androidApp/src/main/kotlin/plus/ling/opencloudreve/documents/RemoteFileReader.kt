package plus.ling.opencloudreve.documents

import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.FileItem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 只读代理文件描述符的回调：系统按偏移读取时，用 `Range` 请求签名下载地址。
 *
 * - 回调运行在独立的 [thread]（每个打开的文件一个），不占用 Binder 线程；
 * - 按 [BLOCK_SIZE] 预读并缓存最近一块，避免顺序读时每次只取几十 KB；
 * - 签名地址在 `expires` 前 [URL_REFRESH_MARGIN_MS] 重新获取；请求被拒（401/403/410）时强制刷新重试一次；
 * - 直链不支持 Range（返回 200）时，把整个响应写入缓存文件，之后都从本地读。
 */
internal class RemoteFileReader(
    private val files: FileRepository,
    private val file: FileItem,
    private val siteUrl: String,
    private val cacheDir: File,
    private val http: OkHttpClient,
    private val thread: HandlerThread,
) : ProxyFileDescriptorCallback() {
    private var url: String? = null
    private var urlExpiresAt = Long.MAX_VALUE
    private var block: ByteArray? = null
    private var blockStart = -1L
    private var blockLength = 0
    private var localFile: File? = null
    private var local: RandomAccessFile? = null

    override fun onGetSize(): Long = file.size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0 || offset >= file.size) return 0
        val want = minOf(size.toLong(), file.size - offset).toInt()
        try {
            var done = 0
            while (done < want) {
                val pos = offset + done
                local?.let { return done + readLocal(it, pos, want - done, data, done) }
                if (pos < blockStart || pos >= blockStart + blockLength) fill(pos)
                if (local != null) continue
                val buf = block ?: throw IOException("block not loaded")
                val inBlock = (pos - blockStart).toInt()
                val n = minOf(blockLength - inBlock, want - done)
                if (n <= 0) throw IOException("short block at $pos")
                System.arraycopy(buf, inBlock, data, done, n)
                done += n
            }
            return done
        } catch (e: ErrnoException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Read ${file.path} @$offset failed", e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        try {
            local?.close()
        } catch (_: IOException) {
        }
        local = null
        localFile?.delete()
        localFile = null
        block = null
        thread.quitSafely()
    }

    private fun readLocal(raf: RandomAccessFile, pos: Long, length: Int, data: ByteArray, dataOffset: Int): Int {
        raf.seek(pos)
        var done = 0
        while (done < length) {
            val n = raf.read(data, dataOffset + done, length - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    /** 取从 [pos] 开始的一块；服务端不支持 Range 时转为整文件缓存 */
    private fun fill(pos: Long) {
        val end = minOf(pos + BLOCK_SIZE, file.size) - 1
        var refreshed = false
        while (true) {
            val request = Request.Builder()
                .url(currentUrl(force = refreshed))
                .header("Range", "bytes=$pos-$end")
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 206 -> {
                        val length = (end - pos + 1).toInt()
                        val buf = block ?: ByteArray(BLOCK_SIZE).also { block = it }
                        val body = response.body ?: throw IOException("empty body")
                        val read = readFully(body.byteStream(), buf, length)
                        if (read <= 0) throw IOException("empty range response")
                        blockStart = pos
                        blockLength = read
                        return
                    }
                    response.code == 200 -> {
                        cacheWhole(response)
                        return
                    }
                    !refreshed && response.code in REFRESHABLE_CODES -> refreshed = true
                    else -> throw IOException("HTTP ${response.code}")
                }
            }
        }
    }

    /** 不支持 Range 的直链：把整个响应写入缓存文件 */
    private fun cacheWhole(response: Response) {
        val body = response.body ?: throw IOException("empty body")
        val dir = File(cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        try {
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        localFile = target
        local = RandomAccessFile(target, "r")
        block = null
        blockLength = 0
        blockStart = -1
    }

    private fun currentUrl(force: Boolean): String {
        val now = System.currentTimeMillis()
        url?.let { if (!force && urlExpiresAt - now > URL_REFRESH_MARGIN_MS) return it }
        val response = runBlocking { withTimeout(URL_TIMEOUT_MS) { files.fileUrl(file, download = true) } }
        val raw = response.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() } ?: throw IOException("empty url")
        val absolute = if (raw.startsWith("/")) siteUrl.trimEnd('/') + raw else raw
        url = absolute
        urlExpiresAt = response.expires?.toEpochMilliseconds() ?: Long.MAX_VALUE
        return absolute
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var done = 0
        input.use {
            while (done < length) {
                val n = it.read(buf, done, length - done)
                if (n < 0) break
                done += n
            }
        }
        return done
    }

    companion object {
        private const val TAG = "RemoteFileReader"
        private const val BLOCK_SIZE = 1 shl 20
        private const val URL_REFRESH_MARGIN_MS = 60_000L
        private const val URL_TIMEOUT_MS = 30_000L
        private val REFRESHABLE_CODES = setOf(401, 403, 410)

        /** 不支持 Range 时的整文件缓存目录（相对 cacheDir） */
        const val CACHE_DIR = "documents-read"
    }
}
