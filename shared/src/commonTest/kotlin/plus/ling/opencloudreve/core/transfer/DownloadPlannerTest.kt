package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadPlannerTest {
    private val fileUrl = "$SITE/api/v4/file/url"

    private fun urlResponse(n: Int, expires: String) =
        """{"urls":[{"url":"$SITE/api/v4/file/content/e1/0/a.bin?sign=s$n"}],"expires":"$expires"}"""

    @Test
    fun prepareResolvesAndCachesUrl() = runTest {
        var n = 0
        val env = TransferEnv { r ->
            check(r.method == "POST" && r.url == fileUrl) { "unexpected ${r.url}" }
            n++
            // 1 小时后过期（NOW = 2023-11-14T22:13:20Z）
            ok(urlResponse(n, "2023-11-14T23:13:20Z"))
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val first = env.downloads.prepare(task.id)
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s1", first.url)
        assertEquals("/dl/a.bin", first.destPath)
        val body = Json.parseToJsonElement(env.requests.single().body).jsonObject
        assertEquals("""["cloudreve://my/a.bin"]""", body["uris"].toString())
        assertEquals("true", body["download"]!!.jsonPrimitive.content)
        assertEquals(0, first.resumeOffset)
        assertTrue(first.headers.isEmpty())
        assertEquals(TransferState.RUNNING, env.repository.task(task.id)!!.state)

        env.downloads.onProgress(task.id, 4)
        val resumed = env.downloads.prepare(task.id)
        assertEquals(1, n, "URL 未临近过期时复用")
        assertEquals(4, resumed.resumeOffset)
        assertEquals(mapOf("Range" to "bytes=4-"), resumed.headers)

        env.nowMs += 3_600_000 - 30_000 // 距过期 30 秒
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s2", env.downloads.prepare(task.id).url)
    }

    @Test
    fun forbiddenDropsUrlAndRetriesThenFails() = runTest {
        var n = 0
        val env = TransferEnv { n++; ok(urlResponse(n, "2030-01-01T00:00:00Z")) }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        assertEquals(TransferOutcome.RetryAfter(1000), env.downloads.onFailed(task.id, 403, "expired"))
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s2", env.downloads.prepare(task.id).url)
        assertEquals(TransferOutcome.RetryAfter(2000), env.downloads.onFailed(task.id, null, "reset"))
        assertEquals(TransferOutcome.RetryAfter(4000), env.downloads.onFailed(task.id, 503, "busy"))
        assertIs<TransferOutcome.Failed>(env.downloads.onFailed(task.id, 503, "busy"))
        assertEquals(TransferState.FAILED, env.repository.task(task.id)!!.state)
    }

    @Test
    fun notFoundFailsImmediatelyAndCompletionUpdatesSummary() = runTest {
        val env = TransferEnv { ok(urlResponse(1, "2030-01-01T00:00:00Z")) }
        val a = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val b = env.repository.createDownload(ACCOUNT, "cloudreve://my/b.bin", "b.bin", 30, "/dl/b.bin")
        env.downloads.prepare(a.id)
        assertIs<TransferOutcome.Failed>(env.downloads.onFailed(a.id, 404, "gone"))
        env.downloads.prepare(b.id)
        env.downloads.onProgress(b.id, 15)
        assertEquals(TransferSummary(activeUploads = 0, activeDownloads = 1, failed = 1, bytesDone = 15, bytesTotal = 30), env.repository.observeSummary(ACCOUNT).first())
        env.downloads.onCompleted(b.id, 30)
        val done = env.repository.task(b.id)!!
        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(1.0, done.progress)
        assertEquals(0, env.repository.observeSummary().first().activeCount)
        env.repository.clearFinished(ACCOUNT)
        assertEquals(listOf(a.id), env.repository.observeTasks(ACCOUNT).first().map { it.id })
    }

    @Test
    fun urlErrorMarksFailed() = runTest {
        val env = TransferEnv {
            respondAggregated()
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val e = assertFailsWith<TransferFailedException> { env.downloads.prepare(task.id) }
        assertEquals(40044, e.code, "单个 uri 的 40081 拆出内层错误")
        assertEquals(40044, env.repository.task(task.id)!!.errorCode)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondAggregated() = raw(
        """{"code":40081,"msg":"batch","aggregated_error":{"cloudreve://my/a.bin":{"code":40044,"msg":"not found"}}}""",
    )
}
