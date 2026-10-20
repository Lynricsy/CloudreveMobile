package plus.ling.opencloudreve.core.files

import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.apiPath
import plus.ling.opencloudreve.core.api.bodyText
import plus.ling.opencloudreve.core.model.FileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailCacheTest {

    private fun file(json: String) = CloudreveJson.decodeFromString(FileItem.serializer(), json)

    @Test
    fun foldersAndDisabledFilesNeverRequest() = runTest {
        val env = DataEnv { okData("""{"url":"https://t/x","expires":null}""") }
        assertNull(env.files.thumbUrl(file(fileJson("dir", folder = true))))
        assertNull(env.files.thumbUrl(file(fileJson("a.png", metadata = """{"thumb:disabled":""}"""))))
        assertTrue(env.server.requests.isEmpty())
    }

    @Test
    fun cachedUntilShortlyBeforeExpiry() = runTest {
        var n = 0
        val env = DataEnv { req ->
            n++
            // 过期时间：当前时钟 + 120 秒
            okData("""{"url":"https://t/thumb-$n","expires":"2023-11-14T22:15:20Z"}""")
        }
        val f = file(fileJson("a.png"))
        assertEquals("https://t/thumb-1", env.files.thumbUrl(f, contextHint = "hint"))
        val req = env.server.requests.single()
        assertEquals("/file/thumb", req.apiPath)
        assertEquals("cloudreve://my/a.png", req.url.parameters["uri"])
        assertEquals("hint", req.headers[ApiClient.HEADER_CONTEXT_HINT])

        // 距过期 > 30 秒：命中缓存
        env.nowMs += 60_000
        assertEquals("https://t/thumb-1", env.files.thumbUrl(f))
        assertEquals(1, env.server.requests.size)

        // 距过期 < 30 秒：重新请求
        env.nowMs += 40_000
        assertEquals("https://t/thumb-2", env.files.thumbUrl(f))
        assertEquals(2, env.server.requests.size)
    }

    @Test
    fun anyErrorMarksDisabledUntilReset() = runTest {
        var fail = true
        val env = DataEnv { req ->
            when (req.apiPath) {
                "/file/thumb" -> if (fail) err(40077, "not exist") else okData("""{"url":"https://t/ok"}""")
                else -> okData()
            }
        }
        val f = file(fileJson("a.psd"))
        assertNull(env.files.thumbUrl(f))
        assertNull(env.files.thumbUrl(f))
        assertTrue(env.files.isThumbDisabled(f))
        assertEquals(1, env.server.requests.size)

        fail = false
        env.files.resetThumbnails(listOf(f.path))
        val patch = env.server.requests.last()
        assertEquals("/file/metadata", patch.apiPath)
        assertTrue(patch.bodyText().contains("\"thumb:disabled\""))
        assertTrue(patch.bodyText().contains("\"remove\":true"))
        assertEquals("https://t/ok", env.files.thumbUrl(f))
    }

    @Test
    fun symbolicFilesUseLinkedUri() = runTest {
        val env = DataEnv { okData("""{"url":"https://t/x"}""") }
        val f = file(fileJson("link.png", metadata = """{"sys:shared_redirect":"cloudreve://abc@share/link.png"}"""))
        env.files.thumbUrl(f)
        assertEquals("cloudreve://abc@share/link.png", env.server.requests.single().url.parameters["uri"])
    }
}
