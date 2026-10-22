package plus.ling.opencloudreve.documents

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 系统文件集成在 Binder 线程上的阻塞 HTTP：持有当前 OkHttp [okhttp3.Call]，
 * 调用方取消（`CancellationSignal`）时直接 `Call.cancel()` 打断连接与正文读取，并给整个请求设总时限。
 * 不用 `withTimeout` 包装阻塞 I/O——协程取消打断不了已阻塞的 socket 读。
 */
internal object CancellableHttp {
    /** 取消回调的注册点（适配 `CancellationSignal.setOnCancelListener`；已取消时注册即回调） */
    fun interface CancelHook {
        fun setOnCancel(action: (() -> Unit)?)
    }

    /**
     * 执行 [request] 并在 [block] 中消费响应（含读取正文）。被取消或超过 [timeoutMs] 时
     * 抛 [InterruptedIOException]。
     */
    fun <T> execute(http: OkHttpClient, request: Request, timeoutMs: Long, cancel: CancelHook?, block: (Response) -> T): T {
        val call = http.newCall(request)
        // 总时限覆盖 DNS、连接、等待与读完正文；持续有少量数据的慢连接也会到点结束
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cancel?.setOnCancel { call.cancel() }
        try {
            return call.execute().use(block)
        } catch (e: IOException) {
            if (call.isCanceled() && e !is InterruptedIOException) {
                throw InterruptedIOException("canceled").apply { initCause(e) }
            }
            throw e
        } finally {
            cancel?.setOnCancel(null)
        }
    }

    /** 把响应正文写入 [target]；失败（含取消）时删除已写的部分 */
    fun writeBody(response: Response, target: File) {
        val body = response.body ?: throw IOException("empty body")
        try {
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it, BUFFER) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
    }

    private const val BUFFER = 1 shl 16
}
