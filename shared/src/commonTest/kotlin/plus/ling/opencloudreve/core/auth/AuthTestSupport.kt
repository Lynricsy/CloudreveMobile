package plus.ling.opencloudreve.core.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import plus.ling.opencloudreve.core.createTestDriver
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.platform.SecretStore
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Instant

/** 内存版安全存储 */
@OptIn(ExperimentalAtomicApi::class)
class MemorySecretStore : SecretStore {
    private val map = AtomicReference(emptyMap<String, ByteArray>())

    override fun put(key: String, value: ByteArray) {
        while (true) {
            val cur = map.load()
            if (map.compareAndSet(cur, cur + (key to value.copyOf()))) return
        }
    }

    override fun get(key: String): ByteArray? = map.load()[key]?.copyOf()

    override fun remove(key: String) {
        while (true) {
            val cur = map.load()
            if (map.compareAndSet(cur, cur - key)) return
        }
    }
}

/** 可手动推进的时钟 */
class TestClock(var now: Instant = Instant.parse("2026-10-20T00:00:00Z")) {
    fun advance(d: Duration) {
        now += d
    }
}

const val SITE = "https://cloud.example.com"

/** 组装一套 auth 组件，HTTP 全部走同一个 MockEngine 处理函数（每个客户端一个新的 MockEngine 实例） */
class AuthHarness(
    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val clock = TestClock()
    val secrets = MemorySecretStore()
    val database = OpenCloudreveDatabase(createTestDriver())
    private val engineFactory = { MockEngine { req -> handler(req) } }
    val identity = ClientIdentity(secrets)
    private val pool = AnonymousClientPool(identity, engineFactory)
    val sessions = SessionStore(secrets, database, pool, clock = { clock.now })
    val registry = ApiClientRegistry(database, sessions, identity, pool, engineFactory)
    val accounts = AccountRepository(database, sessions, registry, clock = { clock.now })
    val login = LoginFlow(registry, accounts)
    val pkce = PkceOAuth(registry, accounts, clock = { clock.now })
}

fun token(tag: String, accessValidFor: Duration, from: Instant, refreshValidFor: Duration = Duration.parse("14d")) = Token(
    accessToken = "access-$tag",
    refreshToken = "refresh-$tag",
    accessExpires = from + accessValidFor,
    refreshExpires = from + refreshValidFor,
)

fun tokenJson(t: Token): String =
    """{"access_token":"${t.accessToken}","refresh_token":"${t.refreshToken}","access_expires":"${t.accessExpires}","refresh_expires":"${t.refreshExpires}"}"""

fun userJson(id: String, email: String = "$id@example.com", nickname: String = "nick-$id"): String =
    """{"id":"$id","email":"$email","nickname":"$nickname","status":"active","anonymous":false}"""

fun MockRequestHandleScope.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.envelope(code: Int, data: String = "null", msg: String = ""): HttpResponseData =
    respondJson("""{"code":$code,"data":$data,"msg":"$msg"}""")

/** 读取请求体文本（JSON / 表单） */
fun HttpRequestData.bodyText(): String = when (val b = body) {
    is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
    else -> ""
}
