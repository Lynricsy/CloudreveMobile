package plus.ling.opencloudreve.core.auth

import app.cash.turbine.test
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.model.User
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class AccountRepositoryTest {
    private val revokes = AtomicInt(0)

    private fun harness() = AuthHarness { req ->
        if (req.method == HttpMethod.Delete && req.url.encodedPath == "/api/v4/session/token") {
            assertTrue(req.bodyText().contains("\"refresh_token\":\"refresh-"))
            revokes.incrementAndFetch()
            envelope(0, "\"\"")
        } else {
            envelope(404)
        }
    }

    @Test
    fun addSwitchAndSignOut() = runTest {
        val h = harness()
        val a = h.accounts.addOrUpdate(SITE, User(id = "u1", email = "a@x", nickname = "A"), token("a", 1.hours, h.clock.now))
        h.clock.advance(1.seconds)
        val b = h.accounts.addOrUpdate("http://nas.local:5212/", User(id = "u1", email = "b@x", nickname = "B"), token("b", 1.hours, h.clock.now), activate = false)

        assertEquals("u1@cloud.example.com", a.id)
        assertEquals("u1@nas.local:5212", b.id)
        assertEquals("http://nas.local:5212", b.siteUrl)
        assertEquals(listOf(a.id, b.id), h.accounts.accounts.value.map { it.id })
        assertEquals(a.id, h.accounts.activeAccount.value?.id)

        h.accounts.switchTo(b.id)
        assertEquals(b.id, h.accounts.activeAccount.value?.id)
        assertFalse(h.accounts.account(a.id)!!.isActive)
        assertFailsWith<IllegalArgumentException> { h.accounts.switchTo("nobody@nowhere") }

        h.accounts.authEvents.test {
            h.accounts.signOut(b.id)
            assertEquals(AuthEvent.SignedOut(b.id, expired = false), awaitItem())
        }
        assertEquals(1, revokes.load())
        assertNull(h.sessions.token(b.id))
        assertNull(h.accounts.account(b.id))
        assertFailsWith<SessionExpiredException> { h.registry.client(b.id) }
        // 当前账户被登出后自动切到剩余账户
        assertEquals(a.id, h.accounts.activeAccount.value?.id)
        assertEquals(listOf(a.id), h.accounts.accounts.value.map { it.id })
    }

    @Test
    fun reLoginOverwritesSameAccount() = runTest {
        val h = harness()
        val first = h.accounts.addOrUpdate(SITE, User(id = "u1", email = "a@x", nickname = "Old"), token("1", 1.hours, h.clock.now))
        h.clock.advance(10.seconds)
        val second = h.accounts.addOrUpdate("$SITE/", User(id = "u1", email = "a@x", nickname = "New"), token("2", 1.hours, h.clock.now))
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("New", second.nickname)
        assertEquals(1, h.accounts.accounts.value.size)
        assertEquals("access-2", h.sessions.token(first.id)?.accessToken)
    }

    @Test
    fun firstAccountBecomesActiveEvenWithoutActivate() = runTest {
        val h = harness()
        val a = h.accounts.addOrUpdate(SITE, User(id = "u1"), token("a", 1.hours, h.clock.now), activate = false)
        assertTrue(a.isActive)
        assertEquals(a.id, h.accounts.activeAccount.value?.id)
    }

    @Test
    fun signOutRemovesCaches() = runTest {
        val h = harness()
        val a = h.accounts.addOrUpdate(SITE, User(id = "u1"), token("a", 1.hours, h.clock.now))
        h.database.cacheQueries.putListing(a.id, "cloudreve://my", "{}", 1)
        h.database.cacheQueries.putSearch(a.id, "q", 1)
        h.database.cacheQueries.putSiteConfig(a.id, "explorer", "{}", 1)
        h.accounts.signOut(a.id)
        assertNull(h.database.cacheQueries.selectListing(a.id, "cloudreve://my").executeAsOneOrNull())
        assertTrue(h.database.cacheQueries.selectRecentSearches(a.id, 10).executeAsList().isEmpty())
        assertNull(h.database.cacheQueries.selectSiteConfig(a.id, "explorer").executeAsOneOrNull())
        assertNull(h.accounts.activeAccount.value)
    }
}
