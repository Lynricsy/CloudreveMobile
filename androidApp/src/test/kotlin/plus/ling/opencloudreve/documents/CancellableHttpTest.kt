package plus.ling.opencloudreve.documents

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 慢速服务器：声明 100 MB 正文后每 50ms 只发 1 字节（模拟持续有少量数据的媒体下载） */
class CancellableHttpTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = ServerSocket(0)
        dir = Files.createTempDirectory("cancellable-http").toFile()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        socket.use { s ->
                            val input = s.getInputStream().bufferedReader()
                            while (input.readLine().orEmpty().isNotEmpty()) Unit
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000000\r\n\r\n".toByteArray())
                            out.flush()
                            while (true) {
                                out.write('x'.code)
                                out.flush()
                                Thread.sleep(50)
                            }
                        }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun download(target: File, timeoutMs: Long, hook: CancellableHttp.CancelHook?): Pair<Thread, AtomicReference<Throwable?>> {
        val error = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/media.mp4").build()
                CancellableHttp.execute(http, request, timeoutMs, hook) { CancellableHttp.writeBody(it, target) }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        return worker to error
    }

    @Test
    fun callerCancellationAbortsBodyCopyAndDeletesPartialFile() {
        val target = File(dir, "media")
        val listener = AtomicReference<(() -> Unit)?>()
        val registered = CountDownLatch(1)
        val hook = CancellableHttp.CancelHook { action ->
            listener.set(action)
            if (action != null) registered.countDown()
        }
        val (worker, error) = download(target, timeoutMs = 60_000, hook = hook)
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        listener.get()?.invoke()

        worker.join(3_000)
        assertFalse("下载在取消后仍在继续", worker.isAlive)
        assertTrue(error.get() is InterruptedIOException)
        assertFalse(target.exists())
    }

    @Test
    fun totalTimeoutEndsTricklingDownload() {
        val target = File(dir, "media")
        val (worker, error) = download(target, timeoutMs = 500, hook = null)

        worker.join(3_000)
        assertFalse("持续少量数据的下载没有总时限", worker.isAlive)
        assertTrue(error.get() is InterruptedIOException)
        assertFalse(target.exists())
    }
}
