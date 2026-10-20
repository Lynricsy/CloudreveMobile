package plus.ling.opencloudreve.core.events

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.MockServer
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.files.err
import plus.ling.opencloudreve.core.model.ExplorerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EventHubTest {

    @Test
    fun parserHandlesFieldsCommentsAndMultilineData() {
        val p = SseParser()
        assertNull(p.feed(": keep"))
        assertNull(p.feed("event: subscribed"))
        assertEquals(SseFrame("subscribed", ""), p.feed(""))
        assertNull(p.feed("event:event"))
        assertNull(p.feed("data: {\"a\":1,"))
        assertNull(p.feed("data:\"b\":2}\r"))
        assertEquals(SseFrame("event", "{\"a\":1,\n\"b\":2}"), p.feed(""))
        // 空行不产生空消息
        assertNull(p.feed(""))
        assertNull(p.feed("data: tail"))
        assertEquals(SseFrame("message", "tail"), p.finish())
    }

    @Test
    fun streamsEventsAndReconnectsWithResync() = runTest {
        var connection = 0
        val env = DataEnv { _ ->
            connection++
            val body = when (connection) {
                1 -> "event: subscribed\n\n" +
                    "event: keep-alive\n\n" +
                    "event: event\ndata: {\"type\":\"create\",\"file_id\":\"f1\",\"from\":\"/a.txt\"}\n\n"
                // 第二次连接：服务端没有保留离线事件（subscribed 而非 resumed）
                2 -> "event: subscribed\n\n" +
                    "event: event\ndata: {\"type\":\"rename\",\"file_id\":\"f1\",\"from\":\"/a.txt\",\"to\":\"/b.txt\"}\n\n"
                else -> "event: resumed\n\n"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val hub = EventHub(ACCOUNT, env.clients)
        val events = hub.subscribe("cloudreve://my/Docs").take(3).toList()

        assertEquals(
            listOf(
                ExplorerEvent(type = "create", fileId = "f1", from = "/a.txt"),
                ExplorerEvent(type = EventHub.EVENT_RESYNC),
                ExplorerEvent(type = "rename", fileId = "f1", from = "/a.txt", to = "/b.txt"),
            ),
            events,
        )
        assertEquals(2, env.server.requests.size)
        env.server.requests.forEach {
            assertEquals("/file/events", it.apiPath)
            assertEquals("cloudreve://my/Docs", it.url.parameters["uri"])
            assertEquals(MockServer.CLIENT_ID, it.headers[ApiClient.HEADER_CLIENT_ID])
            assertEquals("Bearer tok", it.headers[HttpHeaders.Authorization])
            assertEquals("text/event-stream", it.headers[HttpHeaders.Accept])
        }
    }

    @Test
    fun networkErrorsRetryAndFeatureDisabledStopsForever() = runTest {
        var connection = 0
        val env = DataEnv { _ ->
            connection++
            when (connection) {
                1 -> respond("bad gateway", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
                else -> err(40019, "Feature not enabled")
            }
        }
        val hub = EventHub(ACCOUNT, env.clients)
        assertFalse(hub.isDisabled)
        val events = hub.subscribe("cloudreve://my").toList()
        assertTrue(events.isEmpty())
        assertTrue(hub.isDisabled)
        assertEquals(2, connection)
        // 禁用后新的订阅立即结束且不再请求
        assertTrue(hub.subscribe("cloudreve://my").toList().isEmpty())
        assertEquals(2, connection)
    }

    @Test
    fun otherBusinessErrorsEndOnlyThatSubscription() = runTest {
        val env = DataEnv { err(40001, "Requested uri not available") }
        val hub = EventHub(ACCOUNT, env.clients)
        assertTrue(hub.subscribe("cloudreve://my/missing").toList().isEmpty())
        assertFalse(hub.isDisabled)
    }

    @Test
    fun backoffDoublesUpToThirtySeconds() {
        val hub = EventHub(ACCOUNT, DataEnv { err(0) }.clients)
        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds },
            (0..6).map { hub.backoff(it) },
        )
        assertEquals(30.seconds, hub.backoff(1000))
    }
}
