package plus.ling.opencloudreve.ui.viewer

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 模拟 PdfRenderer：页面打开期间关闭会抛异常（与平台行为一致），关闭后再打开页面也会抛异常 */
private class FakeRenderer {
    @Volatile var openPages = 0
    @Volatile var closed = false

    fun renderPage(onOpened: () -> Unit): String {
        check(!closed) { "renderer already closed" }
        openPages++
        try {
            onOpened()
            return "bitmap"
        } finally {
            openPages--
        }
    }

    fun close() {
        check(openPages == 0) { "close with open page" }
        closed = true
    }
}

class SerialResourceTest {
    @Test
    fun closeDuringInFlightRenderWaitsForThePageToFinish() = runBlocking {
        val renderer = FakeRenderer()
        val released = CountDownLatch(1)
        var releaseError: Throwable? = null
        val resource = SerialResource(renderer) {
            try {
                it.close()
            } catch (e: Throwable) {
                releaseError = e
            } finally {
                released.countDown()
            }
        }
        val pageOpened = CountDownLatch(1)
        val finishPage = CountDownLatch(1)
        val render = async(kotlinx.coroutines.Dispatchers.Default) {
            resource.use { r -> r.renderPage { pageOpened.countDown(); finishPage.await() } }
        }
        assertTrue(pageOpened.await(5, TimeUnit.SECONDS))

        try {
            // 主线程 onDispose 里关闭：不阻塞，也不在页面打开期间关闭渲染器
            resource.close()
            assertFalse(released.await(200, TimeUnit.MILLISECONDS))
            assertFalse(renderer.closed)
        } finally {
            // 断言失败时也放行在途渲染，避免 runBlocking 等待子协程而挂住
            finishPage.countDown()
        }
        assertEquals("bitmap", render.await())
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertNull(releaseError)
        assertTrue(renderer.closed)
    }

    @Test
    fun useAfterCloseDoesNotTouchTheReleasedRenderer() = runBlocking {
        val renderer = FakeRenderer()
        val released = CountDownLatch(1)
        val resource = SerialResource(renderer) { it.close(); released.countDown() }
        resource.close()
        assertTrue(released.await(5, TimeUnit.SECONDS))

        var touched = false
        assertNull(resource.use { touched = true; it.renderPage {} })
        assertFalse(touched)
    }
}
