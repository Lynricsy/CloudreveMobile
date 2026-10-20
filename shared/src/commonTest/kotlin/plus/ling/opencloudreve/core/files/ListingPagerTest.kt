package plus.ling.opencloudreve.core.files

import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.api.apiPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingPagerTest {

    private fun listJson(names: List<String>, pagination: String) =
        """{"files":[${names.joinToString(",") { fileJson(it) }}],"parent":{"type":1,"id":"root","name":"","path":"cloudreve://my"},""" +
            """"pagination":$pagination,"props":{"capability":"/f+3","max_page_size":2000,"order_by_options":["name","created_at"],"order_direction_options":["asc","desc"]},""" +
            """"context_hint":"hint-1","view":{"page_size":100,"order":"name","order_direction":"desc","view":"list","thumbnail":false}}"""

    @Test
    fun cursorPagesAccumulateUntilNextTokenMissing() = runTest {
        val env = DataEnv { req ->
            when (req.url.parameters["next_page_token"]) {
                null -> okData(listJson(listOf("a", "b"), """{"page":0,"page_size":2,"next_token":"t2","is_cursor":true}"""))
                "t2" -> okData(listJson(listOf("c", "b"), """{"page":0,"page_size":2,"next_token":"t3","is_cursor":true}"""))
                else -> okData(listJson(listOf("d"), """{"page":0,"page_size":2,"is_cursor":true}"""))
            }
        }
        val pager = env.files.pager("cloudreve://my", pageSize = 60, orderBy = "name", orderDirection = "asc")

        val first = pager.refresh()
        assertEquals(listOf("a", "b"), first.files.map { it.name })
        assertTrue(first.hasMore)
        assertEquals("hint-1", first.contextHint)
        assertEquals("name", first.view?.order)

        pager.loadMore()
        val last = pager.loadMore()
        // 重复的 b 按 path 去重
        assertEquals(listOf("a", "b", "c", "d"), last.files.map { it.name })
        assertFalse(last.hasMore)
        assertEquals(3, last.loadedPages)

        // 没有更多时不再请求
        pager.loadMore()
        assertEquals(3, env.server.requests.size)
        val tokens = env.server.requests.map { it.url.parameters["next_page_token"] }
        assertEquals(listOf(null, "t2", "t3"), tokens)
        env.server.requests.forEach {
            assertEquals("/file", it.apiPath)
            assertEquals("60", it.url.parameters["page_size"])
            assertEquals("name", it.url.parameters["order_by"])
            assertNull(it.url.parameters["page"])
        }
    }

    @Test
    fun pageModeUsedWhenIsCursorMissingAndTotalItemsPresent() = runTest {
        val env = DataEnv { req ->
            when (req.url.parameters["page"]) {
                null -> okData(listJson(listOf("a", "b"), """{"page":0,"page_size":2,"total_items":5}"""))
                "1" -> okData(listJson(listOf("c", "d"), """{"page":1,"page_size":2,"total_items":5}"""))
                else -> okData(listJson(listOf("e"), """{"page":2,"page_size":2,"total_items":5}"""))
            }
        }
        val pager = env.files.pager("cloudreve://my")
        assertTrue(pager.refresh().hasMore)
        assertTrue(pager.loadMore().hasMore)
        val last = pager.loadMore()
        assertFalse(last.hasMore)
        assertEquals(listOf("a", "b", "c", "d", "e"), last.files.map { it.name })
        assertEquals(listOf(null, "1", "2"), env.server.requests.map { it.url.parameters["page"] })
        assertTrue(env.server.requests.all { it.url.parameters["next_page_token"] == null })
    }

    @Test
    fun refreshResetsAccumulationAndListingCacheIsWritten() = runTest {
        var calls = 0
        val env = DataEnv { req ->
            calls++
            if (req.url.parameters["next_page_token"] == null) {
                okData(listJson(listOf("a"), """{"page":0,"page_size":50,"next_token":"t2","is_cursor":true}"""))
            } else {
                okData(listJson(listOf("b"), """{"page":0,"page_size":50,"is_cursor":true}"""))
            }
        }
        val pager = env.files.pager("cloudreve://my")
        pager.refresh()
        pager.loadMore()
        val cached = assertNotNull(env.files.cachedListing("cloudreve://my"))
        assertEquals(listOf("a", "b"), cached.files.map { it.name })

        val again = pager.refresh()
        assertEquals(listOf("a"), again.files.map { it.name })
        assertTrue(again.hasMore)
        assertEquals(3, calls)

        // 新 pager 可用缓存预填
        val other = env.files.pager("cloudreve://my")
        assertTrue(other.primeFromCache())
        assertTrue(other.state.value.fromCache)
    }

    @Test
    fun searchListingsAreNotCached() = runTest {
        val env = DataEnv { okData(listJson(listOf("a"), """{"page":0,"page_size":50,"is_cursor":true}""")) }
        val uri = "cloudreve://my?name=a"
        env.files.pager(uri).refresh()
        assertNull(env.files.cachedListing(uri))
    }
}
