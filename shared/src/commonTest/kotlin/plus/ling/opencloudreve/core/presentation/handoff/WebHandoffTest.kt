package plus.ling.opencloudreve.core.presentation.handoff

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebHandoffTest {

    @Test
    fun buildsHandoffUrlWithEncodedRedirect() {
        val redirect = WebHandoff.viewerRedirect("cloudreve://my/文档/a b", "Xy12", "drawio", "ent+1")
        assertEquals(
            "/home?path=cloudreve%3A%2F%2Fmy%2F%E6%96%87%E6%A1%A3%2Fa%20b&open=Xy12&viewer=drawio&version=ent%2B1",
            redirect,
        )
        val url = WebHandoff.buildUrl("https://cloud.example.com/", "c0de-_x", redirect)
        assertEquals(
            "https://cloud.example.com/session/handoff?code=c0de-_x&redirect=" +
                "%2Fhome%3Fpath%3Dcloudreve%253A%252F%252Fmy%252F%25E6%2596%2587%25E6%25A1%25A3%252Fa%2520b" +
                "%26open%3DXy12%26viewer%3Ddrawio%26version%3Dent%252B1",
            url,
        )
        // 网页端只接受站内路径：其它一律回到 /home
        assertEquals("https://s/session/handoff?code=c&redirect=%2Fhome", WebHandoff.buildUrl("https://s", "c", "//evil.com"))
        assertEquals("https://s/session/handoff?code=c&redirect=%2Fhome", WebHandoff.buildUrl("https://s", "c", "https://evil.com"))
        assertEquals("/admin/node/3", WebHandoff.adminRedirect(AdminWebSection.Nodes, 3))
        assertEquals("/admin/user", WebHandoff.adminRedirect(AdminWebSection.Users, 3))
        assertEquals("/admin/home", WebHandoff.adminRedirect(AdminWebSection.Home))
    }

    @Test
    fun refusesOldServersAndCachesVersion() = runTest {
        var pings = 0
        var codes = 0
        var version = "4.19.10"
        val handoff = WebHandoff(
            siteUrlOf = { "https://s" },
            ping = { pings++; version },
            createCode = { codes++; "code$codes" },
        )
        assertEquals(HandoffAvailability.Unsupported, handoff.availability("a"))
        val e = assertFailsWith<HandoffUnsupportedException> { handoff.url("a", "/home") }
        assertEquals("4.19.10", e.serverVersion)
        assertEquals(0, codes)
        assertEquals(1, pings)

        version = "4.19.11"
        assertEquals("4.19.11", handoff.serverVersion("a", forceRefresh = true))
        assertEquals("https://s/session/handoff?code=code1&redirect=%2Fhome", handoff.url("a", "/home"))
        // 每次都取新的一次性 code，版本走缓存
        assertEquals("https://s/session/handoff?code=code2&redirect=%2Fhome", handoff.url("a", "/home"))
        assertEquals(2, pings)
    }
}
