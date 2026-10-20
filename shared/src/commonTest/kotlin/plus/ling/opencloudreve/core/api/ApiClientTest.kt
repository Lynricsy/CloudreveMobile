package plus.ling.opencloudreve.core.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {

    @Serializable
    private data class Item(val name: String, val size: Long = 0)

    /** 假 AuthProvider：access token 固定为 old，刷新后变为 new；刷新本身单飞并计数 */
    private class FakeAuth(private val refreshResult: String? = "new") : AuthProvider {
        private val mutex = Mutex()
        var current = "old"
        var refreshCalls = 0
        var rejectedTokens = mutableListOf<String>()

        override suspend fun validAccessToken(): String = current

        override suspend fun refreshAfterRejection(rejectedToken: String): String? = mutex.withLock {
            rejectedTokens += rejectedToken
            if (rejectedToken != current) return current // 别人已刷新过，直接用新的
            refreshCalls++
            refreshResult?.also { current = it }
        }
    }

    @Test
    fun decodesDataAndSendsClientHeaders() = runTest {
        val server = MockServer { json("""{"code":0,"msg":"","data":{"name":"a.txt","size":3,"extra":true}}""") }
        val client = server.client(FakeAuth())
        val item = client.get<Item>("/file/info", mapOf("uri" to "cloudreve://my/a.txt", "skip" to null, "flag" to true))
        assertEquals(Item("a.txt", 3), item)
        val req = server.requests.single()
        assertEquals("/api/v4/file/info", req.url.encodedPath)
        assertEquals("cloudreve://my/a.txt", req.url.parameters["uri"])
        assertEquals("true", req.url.parameters["flag"])
        assertNull(req.url.parameters["skip"])
        assertEquals(MockServer.CLIENT_ID, req.headers[ApiClient.HEADER_CLIENT_ID])
        assertEquals("Bearer old", req.headers[HttpHeaders.Authorization])
        assertTrue(!req.headers[ApiClient.HEADER_CORRELATION_ID].isNullOrEmpty())
    }

    @Test
    fun missingDataIsToleratedForUnitAndNullable() = runTest {
        val server = MockServer { json("""{"code":0,"msg":""}""") }
        val client = server.client()
        client.delete<Unit, Map<String, List<String>>>("/file", mapOf("uris" to listOf("cloudreve://my/a")))
        assertNull(client.get<Item?>("/x"))
        assertEquals("""{"uris":["cloudreve://my/a"]}""", server.requests[0].bodyText())
        assertEquals(HttpMethod.Delete, server.requests[0].method)
        // 匿名客户端不带 Authorization
        assertNull(server.requests[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun noAuthSkipsToken() = runTest {
        val server = MockServer { json("""{"code":0,"data":"4.19.11","msg":""}""") }
        val client = server.client(FakeAuth())
        assertEquals("4.19.11", client.get<String>("/site/ping", noAuth = true))
        assertNull(server.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun errorCodeThrowsApiException() = runTest {
        val server = MockServer { json("""{"code":40004,"msg":"Object existed","correlation_id":"c-1"}""") }
        val e = assertFailsWith<ApiException> { server.client().get<Item>("/x") }
        assertEquals(40004, e.code)
        assertEquals("Object existed", e.serverMsg)
        assertEquals("c-1", e.correlationId)
    }

    @Test
    fun code203IsThrownWithData() = runTest {
        val server = MockServer { json("""{"code":203,"msg":"","data":"2fa-session"}""") }
        val e = assertFailsWith<ApiException> { server.client().get<Item>("/session/token") }
        assertEquals(ErrorCode.NOT_FULLY_SUCCESS, e.code)
        assertEquals("2fa-session", e.data!!.jsonPrimitive.content)
    }

    @Test
    fun lockConflictParsesConflicts() = runTest {
        val server = MockServer {
            json(
                """{"code":40073,"msg":"Lock conflict","data":[
                    {"path":"/a.txt","token":"tok-1","type":0,"owner":{"application":{"type":"webdav"}}},
                    {"path":"/dir","token":"tok-2","type":1,"owner":{"application":{"type":"viewer","viewer_id":"monaco"}}}
                ]}""",
            )
        }
        val e = assertFailsWith<LockConflictException> { server.client().get<Item>("/x") }
        assertEquals(ErrorCode.LOCK_CONFLICT, e.code)
        assertEquals(listOf("tok-1", "tok-2"), e.conflicts.map { it.token })
        assertEquals("webdav", e.conflicts[0].owner?.application?.type)
        assertEquals("monaco", e.conflicts[1].owner?.application?.viewerId)
        assertEquals(1, e.conflicts[1].type)
    }

    @Test
    fun batchPartialFailureCarriesAggregatedErrors() = runTest {
        val server = MockServer {
            json(
                """{"code":40081,"msg":"Batch not fully completed","aggregated_error":{
                    "cloudreve://my/a":{"code":40044,"msg":"File not found"},
                    "cloudreve://my/b":{"code":403,"msg":"denied"}}}""",
            )
        }
        val e = assertFailsWith<ApiException> {
            server.client().post<Unit, Map<String, String>>("/file/move", mapOf("dst" to "cloudreve://my"))
        }
        assertEquals(ErrorCode.BATCH_PARTIAL, e.code)
        val aggregated = e.aggregated!!
        assertEquals(40044, aggregated["cloudreve://my/a"]!!.code)
        assertEquals("denied", aggregated["cloudreve://my/b"]!!.msg)
    }

    @Test
    fun nonEnvelopeResponseIsTransportError() = runTest {
        val server = MockServer { json("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, "text/html") }
        val e = assertFailsWith<TransportException> { server.client().get<Item>("/x") }
        assertEquals(502, e.httpStatus)
        assertEquals(ErrorCode.BAD_RESPONSE, e.code)
    }

    @Test
    fun unauthorizedRefreshesOnceAndReplays() = runTest {
        val auth = FakeAuth()
        val server = MockServer { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer new") {
                json("""{"code":0,"msg":"","data":{"name":"ok"}}""")
            } else {
                json("""{"code":401,"msg":"Login required"}""")
            }
        }
        val client = server.client(auth)
        assertEquals("ok", client.get<Item>("/user/me").name)
        assertEquals(1, auth.refreshCalls)
        assertEquals(listOf("Bearer old", "Bearer new"), server.requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun concurrentUnauthorizedRequestsShareOneRefresh() = runTest {
        val auth = FakeAuth()
        // 闸门：5 个旧 token 请求都到达服务端后才一起返回 401，确保它们真正并发被拒
        val gate = CompletableDeferred<Unit>()
        val arrived = Mutex()
        var oldCount = 0
        val server = MockServer { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer new") {
                json("""{"code":0,"msg":"","data":{"name":"${req.url.parameters["i"]}"}}""")
            } else {
                arrived.withLock { if (++oldCount == 5) gate.complete(Unit) }
                gate.await()
                json("""{"code":401,"msg":"Login required"}""")
            }
        }
        val client = server.client(auth)
        val names = (1..5).map { i -> async { client.get<Item>("/user/me", mapOf("i" to i)).name } }.awaitAll()
        assertEquals((1..5).map { it.toString() }, names)
        // 每个请求都以被拒绝的旧 token 请求刷新一次，由 AuthProvider 单飞，实际只刷新一次
        assertEquals(5, auth.rejectedTokens.size)
        assertTrue(auth.rejectedTokens.all { it == "old" })
        assertEquals(1, auth.refreshCalls)
        // 5 次被拒 + 5 次重放
        assertEquals(10, server.requests.size)
    }

    @Test
    fun replayHappensAtMostOnce() = runTest {
        val auth = FakeAuth()
        val server = MockServer { json("""{"code":401,"msg":"Login required"}""") }
        val e = assertFailsWith<ApiException> { server.client(auth).get<Item>("/user/me") }
        assertEquals(ErrorCode.LOGIN_REQUIRED, e.code)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun failedRefreshDoesNotReplay() = runTest {
        val auth = FakeAuth(refreshResult = null)
        val server = MockServer { json("""{"code":401,"msg":"Login required"}""") }
        assertFailsWith<ApiException> { server.client(auth).get<Item>("/user/me") }
        assertEquals(1, server.requests.size)
        assertEquals(1, auth.refreshCalls)
    }

    @Test
    fun anonymousUnauthorizedDoesNotRefresh() = runTest {
        val auth = FakeAuth()
        val server = MockServer { json("""{"code":401,"msg":"Login required"}""") }
        assertFailsWith<ApiException> { server.client(auth).get<Item>("/x", noAuth = true) }
        assertEquals(0, auth.refreshCalls)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun rawBytesBodyAlsoRefreshesAndReplays() = runTest {
        val auth = FakeAuth()
        val server = MockServer { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer new") json("""{"code":0,"msg":""}""")
            else json("""{"code":401,"msg":"Login required"}""")
        }
        val bytes = byteArrayOf(1, 2, 3, 0, -1)
        server.client(auth).callBytes(HttpMethod.Put, "/user/setting/avatar", Unit.serializer(), bytes, "image/png")
        assertEquals(2, server.requests.size)
        server.requests.forEach {
            assertTrue(it.bodyBytes().contentEquals(bytes))
            assertEquals("image/png", it.body.contentType.toString())
        }
    }

    @Test
    fun absoluteUrlIsSentAsIs() = runTest {
        val server = MockServer { json("""{"code":0,"msg":"","data":1}""") }
        assertEquals(JsonPrimitive(1), server.client().get<JsonPrimitive>("https://other.example.com/callback/s3/x/y"))
        assertEquals("other.example.com", server.requests.single().url.host)
        assertEquals("/callback/s3/x/y", server.requests.single().url.encodedPath)
    }
}
