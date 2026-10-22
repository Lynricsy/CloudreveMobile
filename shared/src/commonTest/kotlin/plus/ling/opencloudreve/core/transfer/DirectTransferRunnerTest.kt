package plus.ling.opencloudreve.core.transfer

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.model.StoragePolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectTransferRunnerTest {
    private val createUrl = "$SITE/api/v4/file/upload"
    private val fileUrl = "$SITE/api/v4/file/url"
    private val contentUrl = "$SITE/api/v4/file/content/e1/0/a.bin?sign=s"

    private fun isCreate(r: Recorded) = r.method == "PUT" && r.url == createUrl

    private fun TransferEnv.runner() = DirectTransferRunner(repository, clients, planner, downloads, materializer, fs)

    @Test
    fun localMultiChunkUploadSendsEveryChunkAndDeletesTask() = runTest {
        val content = bytes(25)
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_LOCAL, 10))
                r.method == "POST" && r.url.startsWith("$createUrl/$SID/") -> ok()
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        env.fs.sources["/src/a.bin"] = content
        val done = env.runner().uploadFile(ACCOUNT, "/src/a.bin", "a.bin", 25, 1234L, null, "cloudreve://my/dir", false)

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(25, done.bytesDone)
        val chunks = env.requests.filter { it.url.startsWith("$createUrl/$SID/") }
        assertEquals(listOf("$createUrl/$SID/0", "$createUrl/$SID/1", "$createUrl/$SID/2"), chunks.map { it.url })
        assertTrue(chunks.all { it.headers["Authorization"] == "Bearer tok" && it.headers["X-Cr-Client-Id"] == "client-1" })
        // 测试数据都 < 128，按 UTF-8 文本记录后可原样还原
        assertContentEquals(content, chunks.joinToString("") { it.body }.encodeToByteArray())
        assertNull(env.repository.task(done.id), "便捷方法结束后删除任务记录")
    }

    @Test
    fun s3UploadCapturesEtagsCompletesAndCallsBack() = runTest {
        val urls = (1..3).map { "https://s3.test/part$it" }
        val complete = "https://s3.test/complete"
        var part = 0
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_S3, 4, uploadUrls = urls, completeUrl = complete, concurrency = 2))
                r.method == "PUT" && r.url in urls -> respond("", HttpStatusCode.OK, headersOf("ETag", "\"etag-${urls.indexOf(r.url) + 1}\"")).also { part++ }
                r.url == complete -> raw("<CompleteMultipartUploadResult/>", contentType = "application/xml")
                r.url == "$SITE/api/v4/callback/s3/$SID/$SECRET" -> ok()
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val task = env.upload(bytes(10))
        val done = env.runner().upload(task.id)

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(3, part)
        val xml = env.requests.single { it.url == complete }.body
        (1..3).forEach { assertTrue("<PartNumber>$it</PartNumber><ETag>\"etag-$it\"</ETag>" in xml, xml) }
        assertEquals("GET", env.requests.last().method)
        assertEquals("$SITE/api/v4/callback/s3/$SID/$SECRET", env.requests.last().url)
    }

    @Test
    fun uploadConflictPropagates() = runTest {
        val env = TransferEnv { r -> if (isCreate(r)) envelope(ErrorCode.OBJECT_EXIST, "exists") else error("unexpected ${r.url}") }
        val task = env.upload(bytes(5))
        assertFailsWith<UploadConflictException> { env.runner().upload(task.id) }
    }

    @Test
    fun downloadWritesFileAndRetriesServerErrors() = runTest {
        val content = bytes(600_000)
        var attempts = 0
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(content.size.toLong())
                r.method == "POST" && r.url == fileUrl -> ok("""{"urls":[{"url":"$contentUrl"}],"expires":"2030-01-01T00:00:00Z"}""")
                r.method == "GET" && r.url == contentUrl -> if (attempts++ == 0) {
                    respond("busy", HttpStatusCode.ServiceUnavailable)
                } else {
                    respond(content, HttpStatusCode.OK, headersOf("Content-Length", content.size.toString()))
                }
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val done = env.runner().downloadFile(ACCOUNT, "cloudreve://my/a.bin", "a.bin", content.size.toLong(), "/dl/a.bin")

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(content.size.toLong(), done.bytesDone)
        assertEquals(2, attempts)
        assertContentEquals(content, env.fs.files["/dl/a.bin"])
        assertNull(env.repository.task(done.id))
    }

    @Test
    fun downloadFailureDeletesPartialFile() = runTest {
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(10)
                r.method == "POST" && r.url == fileUrl -> ok("""{"urls":[{"url":"$contentUrl"}],"expires":"2030-01-01T00:00:00Z"}""")
                else -> respond("gone", HttpStatusCode.NotFound)
            }
        }
        val error = assertFailsWith<TransferFailedException> {
            env.runner().downloadFile(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 10, "/dl/a.bin")
        }
        assertEquals(TransferErrorCode.STORAGE_REJECTED, error.code)
        assertNull(env.fs.files["/dl/a.bin"])
    }

    /** ReviewSharedCore #1：签名失效的 200 JSON 错误正文不能作为文件交付，刷新 URL 后下载真实字节 */
    @Test
    fun signatureRejectedWith200JsonRefreshesUrlAndDownloadsRealBytes() = runTest {
        val content = bytes(300)
        val stale = "$SITE/api/v4/file/content/e1/0/a.bin?sign=stale"
        val fresh = "$SITE/api/v4/file/content/e1/0/a.bin?sign=fresh"
        var urls = 0
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(content.size.toLong())
                r.method == "POST" && r.url == fileUrl ->
                    ok("""{"urls":[{"url":"${if (urls++ == 0) stale else fresh}"}],"expires":"2030-01-01T00:00:00Z"}""")
                r.url == stale -> raw("""{"code":40020,"msg":"Signature expired","error":"sign expired"}""", contentType = "application/json; charset=utf-8")
                r.url == fresh -> respond(content, HttpStatusCode.OK, headersOf("Content-Length", content.size.toString()))
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val done = env.runner().downloadFile(ACCOUNT, "cloudreve://my/a.bin", "a.bin", content.size.toLong(), "/dl/a.bin")

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(2, urls, "被拒后重新请求签名 URL")
        assertContentEquals(content, env.fs.files["/dl/a.bin"])
    }

    /** ReviewSharedCore #2：旧版本下载一半后文件被同大小的新版本覆盖，续传结果只能是完整旧版本，不能 AABB */
    @Test
    fun resumeAfterOverwriteNeverSplicesTwoVersions() = runTest {
        val old = "AAAA".encodeToByteArray()
        val new = "BBBB".encodeToByteArray()
        val oldUrl = "$SITE/api/v4/file/content/e-old/0/a.bin?sign=s"
        val newUrl = "$SITE/api/v4/file/content/e-new/0/a.bin?sign=s"
        var primary = "e-old"
        val env = TransferEnv { r ->
            when {
                isFileInfo(r) -> fileInfo(4, entity = primary)
                r.method == "POST" && r.url == fileUrl -> {
                    // 未指定 entity 时服务端给当前主版本
                    val entity = Json.parseToJsonElement(r.body).jsonObject["entity"]?.jsonPrimitive?.content ?: primary
                    ok("""{"urls":[{"url":"${if (entity == "e-old") oldUrl else newUrl}"}],"expires":"2030-01-01T00:00:00Z"}""")
                }
                r.method == "GET" && (r.url == oldUrl || r.url == newUrl) -> {
                    val data = if (r.url == oldUrl) old else new
                    val from = r.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    if (from == null) {
                        respond(data, HttpStatusCode.OK, headersOf("Content-Length", "4"))
                    } else {
                        respond(
                            data.copyOfRange(from, 4), HttpStatusCode.PartialContent,
                            headersOf("Content-Range" to listOf("bytes $from-3/4"), "Content-Length" to listOf("${4 - from}")),
                        )
                    }
                }
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val task = env.repository.createDownload(ACCOUNT, "cloudreve://my/a.bin", "a.bin", 4, "/dl/a.bin")
        // 首次下载了旧版本的前半段后签名过期
        env.downloads.prepare(task.id)
        env.fs.files["/dl/a.bin"] = "AA".encodeToByteArray()
        env.downloads.onProgress(task.id, 2)
        env.downloads.onFailed(task.id, 403, "expired")
        primary = "e-new" // 其他客户端覆盖了同一路径

        val done = env.runner().download(task.id)

        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals("AAAA", env.fs.files["/dl/a.bin"]!!.decodeToString())
    }
}
