package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPlannerTest {
    private val fileUrl = "$SITE/api/v4/file/url"

    private fun urlResponse(n: Int, expires: String) =
        """{"urls":[{"url":"$SITE/api/v4/file/content/e1/0/a.bin?sign=s$n"}],"expires":"$expires"}"""

    private fun isUrl(r: Recorded) = r.method == "POST" && r.url == fileUrl

    /** `POST /file/url` 请求体里的 entity（未指定为 null） */
    private fun Recorded.entity(): String? = Json.parseToJsonElement(body).jsonObject["entity"]?.jsonPrimitive?.content

    private fun result(total: Long, status: Int = 200, body: String? = null, type: String? = "application/octet-stream", range: String? = null, offset: Long? = 0) =
        DownloadResult(status, type, range, offset, total, body?.encodeToByteArray())

    @Test
    fun prepareResolvesAndCachesUrl() = runTest {
        var n = 0
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(10)
                isUrl(r) -> {
                    n++
                    // 1 小时后过期（NOW = 2023-11-14T22:13:20Z）
                    ok(urlResponse(n, "2023-11-14T23:13:20Z"))
                }
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val first = env.downloads.prepare(task.id)
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s1", first.url)
        assertEquals("/dl/a.bin", first.destPath)
        val body = Json.parseToJsonElement(env.requests.single(::isUrl).body).jsonObject
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
        assertEquals(1, env.requests.count(::isFileInfo), "实体只在首次准备时固定")
    }

    @Test
    fun forbiddenDropsUrlAndRetriesThenFails() = runTest {
        var n = 0
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(10) else { n++; ok(urlResponse(n, "2030-01-01T00:00:00Z")) } }
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
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(if ("b.bin" in r.url) 30 else 10) else ok(urlResponse(1, "2030-01-01T00:00:00Z")) }
        val a = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val b = env.repository.createDownload(ACCOUNT, "cloudreve://my/b.bin", "b.bin", 30, "/dl/b.bin")
        env.downloads.prepare(a.id)
        assertIs<TransferOutcome.Failed>(env.downloads.onFailed(a.id, 404, "gone"))
        env.downloads.prepare(b.id)
        env.downloads.onProgress(b.id, 15)
        assertEquals(TransferSummary(activeUploads = 0, activeDownloads = 1, failed = 1, bytesDone = 15, bytesTotal = 30), env.repository.observeSummary(ACCOUNT).first())
        assertEquals(TransferOutcome.Continue(true), env.downloads.onCompleted(b.id, result(30)))
        val done = env.repository.task(b.id)!!
        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(1.0, done.progress)
        assertEquals(0, env.repository.observeSummary().first().activeCount)
        env.repository.clearFinished(ACCOUNT)
        assertEquals(listOf(a.id), env.repository.observeTasks(ACCOUNT).first().map { it.id })
    }

    @Test
    fun urlErrorMarksFailed() = runTest {
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(10) else respondAggregated(40044) }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        val e = assertFailsWith<TransferFailedException> { env.downloads.prepare(task.id) }
        assertEquals(40044, e.code, "单个 uri 的 40081 拆出内层错误")
        assertEquals(40044, env.repository.task(task.id)!!.errorCode)
    }

    // ---- ReviewSharedCore #1：签名失效时服务端以 HTTP 200 返回错误信封 ----

    @Test
    fun signatureErrorEnvelopeWith200IsNotCompletedAndUrlIsRefreshed() = runTest {
        var n = 0
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(1000) else { n++; ok(urlResponse(n, "2030-01-01T00:00:00Z")) } }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 1000, "/dl/a.bin")
        env.downloads.prepare(task.id)
        val envelope = """{"code":40020,"msg":"Signature expired","error":"sign expired"}"""

        val outcome = env.downloads.onCompleted(
            task.id,
            result(envelope.length.toLong(), body = envelope, type = "application/json; charset=utf-8"),
        )

        assertEquals(TransferOutcome.RetryAfter(1000), outcome)
        assertEquals(TransferState.RUNNING, env.repository.task(task.id)!!.state, "错误正文不能当作下载完成")
        val again = env.downloads.prepare(task.id)
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s2", again.url, "被拒的签名 URL 已丢弃，重新获取")
        assertEquals(0, again.resumeOffset, "写入的错误正文作废，从 0 开始")
    }

    @Test
    fun jsonFileOfPinnedSizeCompletes() = runTest {
        val content = """{"code":40020,"msg":"just a json file"}"""
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(content.length.toLong()) else ok(urlResponse(1, "2030-01-01T00:00:00Z")) }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.json", "a.json", content.length.toLong(), "/dl/a.json")
        env.downloads.prepare(task.id)
        val outcome = env.downloads.onCompleted(task.id, result(content.length.toLong(), body = content, type = "application/json"))
        assertEquals(TransferOutcome.Continue(true), outcome, "长度与实体一致的合法 JSON 文件正常完成")
        assertEquals(TransferState.COMPLETED, env.repository.task(task.id)!!.state)
    }

    @Test
    fun lengthOrRangeMismatchResetsAndEventuallyFails() = runTest {
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(100) else ok(urlResponse(1, "2030-01-01T00:00:00Z")) }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 100, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.downloads.onProgress(task.id, 40)
        // 206 但起点不是请求的 40：长度凑巧相同也不能拼接
        val misaligned = result(100, status = 206, range = "bytes 0-59/100", offset = 40)
        assertEquals(TransferOutcome.RetryAfter(1000), env.downloads.onCompleted(task.id, misaligned))
        assertEquals(0, env.downloads.prepare(task.id).resumeOffset)
        assertEquals(TransferOutcome.RetryAfter(2000), env.downloads.onCompleted(task.id, result(99)))
        env.downloads.prepare(task.id)
        assertEquals(TransferOutcome.RetryAfter(4000), env.downloads.onCompleted(task.id, result(99)))
        env.downloads.prepare(task.id)
        val failed = env.downloads.onCompleted(task.id, result(99))
        assertIs<TransferOutcome.Failed>(failed)
        assertEquals(TransferErrorCode.STORAGE_REJECTED, env.repository.task(task.id)!!.errorCode)
    }

    // ---- ReviewSharedCore #2：续传固定同一文件实体 ----

    @Test
    fun urlRenewalAfterExpiryKeepsPinnedEntityAndRange() = runTest {
        var n = 0
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(10, entity = "e-old")
                isUrl(r) -> { n++; ok(urlResponse(n, "2030-01-01T00:00:00Z")) }
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.downloads.onProgress(task.id, 4)
        env.downloads.onFailed(task.id, 403, "expired")
        val resumed = env.downloads.prepare(task.id)

        val urlRequests = env.requests.filter(::isUrl)
        assertEquals(listOf("e-old", "e-old"), urlRequests.map { it.entity() }, "续签 URL 总是请求首次固定的实体")
        assertEquals(mapOf("Range" to "bytes=4-"), resumed.headers)
    }

    @Test
    fun deletedPinnedVersionRestartsFromZeroOnCurrentVersion() = runTest {
        var primary = "e-old"
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(if (primary == "e-old") 10 else 12, entity = primary)
                isUrl(r) && r.entity() == "e-old" && primary != "e-old" -> respondAggregated(40077)
                isUrl(r) -> ok(urlResponse(1, "2030-01-01T00:00:00Z"))
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.downloads.onProgress(task.id, 4)
        env.downloads.onFailed(task.id, 403, "expired")
        primary = "e-new" // 旧版本被覆盖并删除

        val request = env.downloads.prepare(task.id)
        assertEquals(0, request.resumeOffset, "旧版本的字节作废")
        assertTrue(request.headers.isEmpty())
        assertEquals("e-new", env.requests.last(::isUrl).entity())
        assertEquals(12, env.repository.task(task.id)!!.size, "任务大小改为新版本大小")
    }

    // ---- ReviewSharedCore #3：URL 失效时一并丢弃内嵌旧 URL 的 resumeData ----

    @Test
    fun rejectedUrlDropsResumeDataSoNextRequestUsesNewUrl() = runTest {
        var n = 0
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(10) else { n++; ok(urlResponse(n, "2030-01-01T00:00:00Z")) } }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.downloads.onProgress(task.id, 6)
        env.repository.setResumeBlob(task.id, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), env.downloads.prepare(task.id).resumeData, "URL 未换时仍用 resumeData")

        env.downloads.onFailed(task.id, 403, "signature expired")
        val next = env.downloads.prepare(task.id)

        assertNull(next.resumeData, "resumeData 内嵌被拒的旧 URL，不能再用")
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s2", next.url)
        assertEquals(0, next.resumeOffset, "进度在 resumeData 里，随之作废")
    }

    @Test
    fun renewedUrlAfterPauseDropsStaleResumeData() = runTest {
        var n = 0
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(10) else { n++; ok(urlResponse(n, "2023-11-14T23:13:20Z")) } }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.repository.setResumeBlob(task.id, byteArrayOf(9))
        env.downloads.pause(task.id)
        env.manager.resume(task.id)
        env.nowMs += 3_600_000 // 暂停期间签名 URL 过期

        val next = env.downloads.prepare(task.id)
        assertEquals("$SITE/api/v4/file/content/e1/0/a.bin?sign=s2", next.url)
        assertNull(next.resumeData)
    }

    // ---- 暂停/取消与网络返回的竞态 ----

    @Test
    fun pauseDuringUrlRequestIsNotOverwrittenByPrepare() = runTest {
        lateinit var env: TransferEnv
        lateinit var taskId: String
        env = TransferEnv { r ->
            if (isFileInfo(r)) return@TransferEnv fileInfo(10)
            env.downloads.pause(taskId) // 用户在请求 URL 期间暂停
            ok(urlResponse(1, "2030-01-01T00:00:00Z"))
        }
        taskId = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin").id

        val e = assertFailsWith<TransferStoppedException> { env.downloads.prepare(taskId) }
        assertEquals(TransferErrorCode.STOPPED, e.code)
        assertEquals(TransferState.PAUSED, env.repository.task(taskId)!!.state)
    }

    @Test
    fun canceledTaskIsNotMarkedCompleted() = runTest {
        val env = TransferEnv { r -> if (isFileInfo(r)) fileInfo(10) else ok(urlResponse(1, "2030-01-01T00:00:00Z")) }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        env.downloads.prepare(task.id)
        env.downloads.cancel(task.id)
        assertEquals(TransferOutcome.Continue(false), env.downloads.onCompleted(task.id, result(10)))
        assertEquals(TransferState.CANCELED, env.repository.task(task.id)!!.state)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondAggregated(code: Int) = raw(
        """{"code":40081,"msg":"batch","aggregated_error":{"cloudreve://my/a.bin":{"code":$code,"msg":"inner"}}}""",
    )
}
