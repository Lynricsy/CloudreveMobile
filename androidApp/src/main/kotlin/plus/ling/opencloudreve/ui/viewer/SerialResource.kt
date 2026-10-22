package plus.ling.opencloudreve.ui.viewer

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 串行持有一个非线程安全的原生资源（如 `PdfRenderer`）：每次使用与最终释放都在同一条专用线程上依次执行。
 *
 * [close] 不阻塞调用线程（可在主线程的 onDispose 里调用）：释放排在已开始/已排队的使用之后，
 * 不会在使用途中关闭资源；关闭后的使用不再触碰资源，[use] 返回 null。
 */
internal class SerialResource<R : Any>(private val resource: R, private val release: (R) -> Unit) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "serial-resource").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)

    /** 在资源线程上执行 [block]；已关闭时返回 null */
    suspend fun <T : Any> use(block: (R) -> T): T? {
        if (closed.get()) return null
        return withContext(dispatcher) { if (closed.get()) null else block(resource) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { release(resource) }
        executor.shutdown()
    }
}
