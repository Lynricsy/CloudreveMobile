package plus.ling.opencloudreve.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 测试用：记录请求并按处理函数返回响应的 MockEngine 封装 */
class MockServer(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    private val lock = Mutex()

    val engine = MockEngine { request ->
        lock.withLock { requests += request }
        handler(request)
    }

    fun client(auth: AuthProvider? = null): ApiClient =
        ApiClient(SITE, CLIENT_ID, auth, engine)

    companion object {
        const val SITE = "https://cloud.example.com"
        const val CLIENT_ID = "11111111-2222-3333-4444-555555555555"
    }
}

/** 返回 JSON 文本 */
fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, contentType: String = "application/json"): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, contentType))

/** 读出请求体文本（JSON/表单/字节） */
fun HttpRequestData.bodyText(): String = when (val b = body) {
    is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
    is OutgoingContent.NoContent -> ""
    else -> error("unsupported body ${b::class}")
}

/** 读出请求体字节 */
fun HttpRequestData.bodyBytes(): ByteArray = when (val b = body) {
    is OutgoingContent.ByteArrayContent -> b.bytes()
    is OutgoingContent.NoContent -> ByteArray(0)
    else -> error("unsupported body ${b::class}")
}

/** 请求路径（去掉 `/api/v4` 前缀） */
val HttpRequestData.apiPath: String get() = url.encodedPath.removePrefix(ApiClient.API_PREFIX)
