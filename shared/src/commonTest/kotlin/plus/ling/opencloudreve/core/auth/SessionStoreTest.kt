package plus.ling.opencloudreve.core.auth

import app.cash.turbine.test
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.model.User
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalAtomicApi::class)
class SessionStoreTest {
    private val user = User(id = "u1", email = "a@example.com", nickname = "A")
    private val refreshCalls = AtomicInt(0)

    private fun HttpRequestData.isRefresh() =
        method == HttpMethod.Post && url.encodedPath.endsWith("/session/token/refresh")

    @Test
    fun freshTokenIsUsedWithoutRefresh() = runTest {
        val h = AuthHarness { envelope(500) }
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 30.minutes, h.clock.now))
        assertEquals("access-old", h.sessions.validAccessToken(acc.id))
    }

    @Test
    fun refreshesWhenLessThanTenMinutesLeftAndSavesNewRefreshToken() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness { req ->
            if (req.isRefresh()) {
                refreshCalls.incrementAndFetch()
                assertTrue(req.bodyText().contains("\"refresh_token\":\"refresh-old\""))
                assertNull(req.headers[HttpHeaders.Authorization])
                envelope(0, tokenJson(token("new", 1.hours, h.clock.now)))
            } else {
                envelope(404)
            }
        }
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 1.hours, h.clock.now))
        h.clock.advance(51.minutes) // 剩 9 分钟
        assertEquals("access-new", h.sessions.validAccessToken(acc.id))
        assertEquals(1, refreshCalls.load())
        assertEquals("refresh-new", h.sessions.token(acc.id)?.refreshToken)
        // 新令牌足够新鲜，不再刷新
        assertEquals("access-new", h.sessions.validAccessToken(acc.id))
        assertEquals(1, refreshCalls.load())
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness { req ->
            if (req.isRefresh()) {
                refreshCalls.incrementAndFetch()
                delay(200.milliseconds)
                envelope(0, tokenJson(token("new", 1.hours, h.clock.now)))
            } else {
                envelope(404)
            }
        }
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 5.minutes, h.clock.now))
        val results = List(10) { async { h.sessions.validAccessToken(acc.id) } }.awaitAll()
        assertEquals(List(10) { "access-new" }, results)
        assertEquals(1, refreshCalls.load())
    }

    @Test
    fun rejectedRequestRefreshesOnceAndReplays() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness { req ->
            when {
                req.isRefresh() -> {
                    refreshCalls.incrementAndFetch()
                    delay(100.milliseconds)
                    envelope(0, tokenJson(token("new", 1.hours, h.clock.now)))
                }
                req.headers[HttpHeaders.Authorization] == "Bearer access-new" -> envelope(0, userJson("u1"))
                else -> envelope(401, msg = "Login required")
            }
        }
        // 本地看起来仍有效，但服务端已拒绝（如服务端时钟/密钥变化）
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 1.hours, h.clock.now))
        val client: ApiClient = h.registry.client(acc.id)
        val users = List(5) { async { client.get<User>("/user/me") } }.awaitAll()
        assertTrue(users.all { it.id == "u1" })
        assertEquals(1, refreshCalls.load())
        assertEquals("access-new", h.sessions.token(acc.id)?.accessToken)
    }

    @Test
    fun credentialInvalidSignsOut() = runTest {
        lateinit var h: AuthHarness
        h = AuthHarness { req ->
            if (req.isRefresh()) {
                refreshCalls.incrementAndFetch()
                envelope(40020, msg = "Invalid refresh token")
            } else {
                envelope(401)
            }
        }
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 1.hours, h.clock.now))
        val client = h.registry.client(acc.id)
        h.accounts.authEvents.test {
            val e = assertFailsWith<ApiException> { client.get<JsonObject>("/user/me") }
            assertEquals(401, e.code)
            assertEquals(AuthEvent.SignedOut(acc.id, expired = true), awaitItem())
        }
        assertEquals(1, refreshCalls.load())
        assertNull(h.sessions.token(acc.id))
        assertNull(h.sessions.validAccessToken(acc.id))
        assertTrue(h.accounts.activeAccount.value!!.sessionExpired)
        assertFailsWith<SessionExpiredException> { h.registry.client(acc.id) }
    }

    @Test
    fun expiredRefreshTokenSignsOutWithoutRequest() = runTest {
        val h = AuthHarness { req ->
            if (req.isRefresh()) refreshCalls.incrementAndFetch()
            envelope(500)
        }
        val acc = h.accounts.addOrUpdate(SITE, user, token("old", 1.hours, h.clock.now, refreshValidFor = 2.hours))
        h.clock.advance(3.hours)
        assertNull(h.sessions.validAccessToken(acc.id))
        assertEquals(0, refreshCalls.load())
        assertTrue(h.accounts.account(acc.id)!!.sessionExpired)
    }
}
