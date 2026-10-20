package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.crypto.AesCtr
import plus.ling.opencloudreve.core.model.EncryptMetadata
import plus.ling.opencloudreve.core.model.StoragePolicy
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadPlannerTest {
    private val createUrl = "$SITE/api/v4/file/upload"

    private fun isCreate(r: Recorded) = r.method == "PUT" && r.url == createUrl

    /** 驱动一个任务直到全部分片完成（不调用 finalize），返回按发出顺序的请求 */
    private suspend fun TransferEnv.sendAll(taskId: String, respond: (ChunkRequest) -> ChunkResponse): List<ChunkRequest> {
        val sent = ArrayList<ChunkRequest>()
        while (true) {
            val batch = planner.nextRequests(taskId)
            if (batch.isEmpty()) break
            batch.forEach { req ->
                sent += req
                val outcome = planner.onChunkDone(taskId, req.index, respond(req))
                assertIs<TransferOutcome.Continue>(outcome)
            }
        }
        assertTrue(planner.allChunksDone(taskId))
        return sent
    }

    @Test
    fun localSingleChunkWhenChunkSizeZero() = runTest {
        val env = TransferEnv { r -> if (isCreate(r)) ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0)) else error("unexpected ${r.url}") }
        val task = env.upload(bytes(100))
        val prepared = env.planner.prepare(task.id)
        assertEquals(TransferState.RUNNING, prepared.state)
        assertEquals(1, prepared.chunkCount)

        val create = Json.parseToJsonElement(env.requests.single().body).jsonObject
        assertEquals("cloudreve://my/dir/a.bin", create["uri"]!!.jsonPrimitive.content)
        assertEquals("100", create["size"]!!.jsonPrimitive.content)
        assertEquals("1234", create["last_modified"]!!.jsonPrimitive.content)
        assertEquals("application/x-test", create["mime_type"]!!.jsonPrimitive.content)
        assertEquals("""["aes-256-ctr"]""", create["encryption_supported"].toString())
        assertNull(create["entity_type"])

        val req = env.planner.nextRequests(task.id).single()
        assertEquals("POST", req.method)
        assertEquals("$SITE/api/v4/file/upload/$SID/0", req.url)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("client-1", req.headers["X-Cr-Client-Id"])
        assertEquals("application/octet-stream", req.headers["Content-Type"])
        assertEquals(ChunkBody.FileRange(0, 100, encrypted = false), req.body)
        assertFalse(req.captureEtag)
        assertTrue(env.planner.nextRequests(task.id).isEmpty(), "并发 1：在途时不再给出请求")

        val outcome = env.planner.onChunkDone(task.id, 0, ChunkResponse(200, body = """{"code":0,"msg":""}"""))
        assertEquals(TransferOutcome.Continue(allChunksDone = true), outcome)
        val done = env.planner.finalize(task.id)
        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(100, done.bytesDone)
        assertEquals(1, env.requests.size, "local 无完成请求/回调")
    }

    @Test
    fun localEmptyFileIsOneZeroLengthChunk() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 10)) }
        val task = env.upload(ByteArray(0))
        assertEquals(1, env.planner.prepare(task.id).chunkCount)
        val req = env.planner.nextRequests(task.id).single()
        assertEquals(ChunkBody.FileRange(0, 0, false), req.body)
        assertEquals(0, env.materializer.readBody(req).size)
    }

    @Test
    fun localMultiChunkHonorsConcurrencyAndRemainder() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 10, concurrency = 2)) }
        val task = env.upload(bytes(25))
        assertEquals(3, env.planner.prepare(task.id).chunkCount)

        val first = env.planner.nextRequests(task.id)
        assertEquals(listOf(0, 1), first.map { it.index })
        assertTrue(env.planner.nextRequests(task.id).isEmpty())
        assertEquals(TransferOutcome.Continue(false), env.planner.onChunkDone(task.id, 1, ChunkResponse(200, body = """{"code":0}""")))
        val second = env.planner.nextRequests(task.id).single()
        assertEquals(2, second.index)
        assertEquals("$SITE/api/v4/file/upload/$SID/2", second.url)
        assertEquals(ChunkBody.FileRange(20, 5, false), second.body)
        assertContentEquals(bytes(25).copyOfRange(20, 25), env.materializer.readBody(second))
        env.planner.onChunkDone(task.id, 2, ChunkResponse(200, body = """{"code":0}"""))
        assertEquals(15, env.repository.task(task.id)!!.bytesDone)
        assertEquals(TransferOutcome.Continue(true), env.planner.onChunkDone(task.id, 0, ChunkResponse(200, body = """{"code":0}""")))
    }

    @Test
    fun relayPolicyUsesLocalBranch() = runTest {
        val env = TransferEnv {
            ok(sessionJson(StoragePolicy.TYPE_S3, 0, uploadUrls = listOf("https://s3/x"), relay = true))
        }
        val task = env.upload(bytes(10))
        assertEquals(StoragePolicy.TYPE_LOCAL, env.planner.prepare(task.id).policyType)
        assertEquals("$SITE/api/v4/file/upload/$SID/0", env.planner.nextRequests(task.id).single().url)
    }

    @Test
    fun localSessionExpiredRestartsTask() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        val req = env.planner.nextRequests(task.id).single()
        val outcome = env.planner.onChunkDone(task.id, req.index, ChunkResponse(200, body = """{"code":40011,"msg":"expired"}"""))
        assertEquals(TransferOutcome.Restart, outcome)
        val item = env.repository.task(task.id)!!
        assertEquals(TransferState.QUEUED, item.state)
        assertNull(item.policyType)
        env.planner.prepare(task.id)
        assertEquals(2, env.requests.count(::isCreate), "重新创建会话")
    }

    @Test
    fun networkErrorsBackOffThenFail() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        val delays = (1..3).map {
            val req = env.planner.nextRequests(task.id).single()
            (env.planner.onChunkFailed(task.id, req.index, "timeout") as TransferOutcome.RetryAfter).delayMillis
        }
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
        val req = env.planner.nextRequests(task.id).single()
        val failed = env.planner.onChunkFailed(task.id, req.index, "timeout")
        assertIs<TransferOutcome.Failed>(failed)
        assertEquals(TransferState.FAILED, env.repository.task(task.id)!!.state)
        assertTrue(env.planner.isResumable(task.id), "local 失败后保留会话以便续传")
    }

    @Test
    fun businessErrorFailsImmediately() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        env.planner.nextRequests(task.id)
        val outcome = env.planner.onChunkDone(task.id, 0, ChunkResponse(200, body = """{"code":40013,"msg":"bad length"}"""))
        assertEquals(TransferOutcome.Failed(40013, "bad length"), outcome)
    }

    @Test
    fun remoteAppendsChunkQueryAndCredential() = runTest {
        val env = TransferEnv {
            ok(sessionJson(StoragePolicy.TYPE_REMOTE, 4, uploadUrls = listOf("https://slave.test/api/v4/slave/upload/$SID?sign=abc"), credential = "Bearer slave-sign"))
        }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        val req = env.planner.nextRequests(task.id).single()
        assertEquals("POST", req.method)
        assertEquals("https://slave.test/api/v4/slave/upload/$SID?sign=abc&chunk=0", req.url)
        assertEquals("Bearer slave-sign", req.headers["Authorization"])
        assertEquals("application/octet-stream", req.headers["Content-Type"])
        env.planner.onChunkDone(task.id, 0, ChunkResponse(200, body = """{"code":0}"""))
        assertEquals("https://slave.test/api/v4/slave/upload/$SID?sign=abc&chunk=1", env.planner.nextRequests(task.id).single().url)
    }

    @Test
    fun oneDriveSequentialContentRangeAndCallback() = runTest {
        val mib = 1024L * 1024
        val size = 12 * mib
        val uploadUrl = "https://graph.test/upload/session"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_ONEDRIVE, 5 * mib, uploadUrls = listOf(uploadUrl), concurrency = 4))
                r.url == "$SITE/api/v4/callback/onedrive/$SID/$SECRET" -> ok()
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val task = env.upload(ByteArray(size.toInt()))
        assertEquals(3, env.planner.prepare(task.id).chunkCount)
        val sent = env.sendAll(task.id) { ChunkResponse(202, body = """{"nextExpectedRanges":[]}""") }
        assertEquals(listOf(0, 1, 2), sent.map { it.index })
        assertTrue(sent.all { it.method == "PUT" && it.url == uploadUrl && it.headers["Authorization"] == null })
        assertEquals("bytes 0-5242879/$size", sent[0].headers["Content-Range"])
        assertEquals("bytes 5242880-10485759/$size", sent[1].headers["Content-Range"])
        assertEquals("bytes 10485760-12582911/$size", sent[2].headers["Content-Range"])
        assertEquals(ChunkBody.FileRange(10 * mib, 2 * mib, false), sent[2].body)

        env.planner.finalize(task.id)
        val callback = env.requests.last()
        assertEquals("POST", callback.method)
        assertEquals("$SITE/api/v4/callback/onedrive/$SID/$SECRET", callback.url)
        assertNull(callback.headers["Authorization"], "回调只靠 callback_secret")
    }

    @Test
    fun oneDriveIsSequentialEvenWithConcurrency() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_ONEDRIVE, 4, uploadUrls = listOf("https://g/u"), concurrency = 8)) }
        val task = env.upload(bytes(12))
        env.planner.prepare(task.id)
        assertEquals(listOf(0), env.planner.nextRequests(task.id).map { it.index })
        assertTrue(env.planner.nextRequests(task.id).isEmpty())
    }

    @Test
    fun oneDriveFragmentOverlapRealignsToExpectedRange() = runTest {
        val mib = 1024L * 1024
        val size = 12 * mib
        val uploadUrl = "https://graph.test/upload/session"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_ONEDRIVE, 5 * mib, uploadUrls = listOf(uploadUrl)))
                r.method == "GET" && r.url == uploadUrl -> raw("""{"expirationDateTime":"x","nextExpectedRanges":["7340032-12582911"]}""")
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val task = env.upload(ByteArray(size.toInt()))
        env.planner.prepare(task.id)
        env.planner.onChunkDone(task.id, env.planner.nextRequests(task.id).single().index, ChunkResponse(202))
        val second = env.planner.nextRequests(task.id).single()
        val overlap = """{"error":{"code":"invalidRange","message":"overlap","innererror":{"code":"fragmentOverlap"}}}"""
        assertEquals(TransferOutcome.Continue(false), env.planner.onChunkDone(task.id, second.index, ChunkResponse(416, body = overlap)))
        assertEquals("GET", env.requests.last().method)
        assertNull(env.requests.last().headers["Content-Range"])

        val resent = env.planner.nextRequests(task.id).single()
        assertEquals(1, resent.index)
        assertEquals("bytes 7340032-10485759/$size", resent.headers["Content-Range"])
        assertEquals(ChunkBody.FileRange(7340032, 10485760 - 7340032, false), resent.body)
        assertEquals(7340032, env.repository.task(task.id)!!.bytesDone)
    }

    @Test
    fun oneDriveRejectsEmptyFile() = runTest {
        val env = TransferEnv { r ->
            if (isCreate(r)) ok(sessionJson(StoragePolicy.TYPE_ONEDRIVE, 4, uploadUrls = listOf("https://g/u"))) else ok()
        }
        val task = env.upload(ByteArray(0))
        val e = assertFailsWith<TransferFailedException> { env.planner.prepare(task.id) }
        assertEquals(TransferErrorCode.ONEDRIVE_EMPTY_FILE, e.code)
        assertEquals("DELETE", env.requests.last().method)
    }

    @Test
    fun s3ThreePartsXmlCompleteAndCallback() = runTest {
        val urls = (1..3).map { "https://s3.test/bucket/key?partNumber=$it&uploadId=u" }
        val complete = "https://s3.test/bucket/key?uploadId=u"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_S3, 4, uploadUrls = urls, completeUrl = complete, concurrency = 3))
                r.url == complete -> raw("<CompleteMultipartUploadResult/>", contentType = "application/xml")
                r.url == "$SITE/api/v4/callback/s3/$SID/$SECRET" -> ok()
                else -> error("unexpected ${r.method} ${r.url}")
            }
        }
        val task = env.upload(bytes(10))
        assertEquals(3, env.planner.prepare(task.id).chunkCount)
        val sent = env.sendAll(task.id) { req -> ChunkResponse(200, etag = "\"etag-${req.index + 1}\"") }
        assertEquals(3, sent.size)
        sent.forEachIndexed { i, req ->
            assertEquals("PUT", req.method)
            assertEquals(urls[i], req.url)
            assertTrue(req.captureEtag)
            assertEquals(mapOf("Content-Type" to "application/octet-stream"), req.headers)
        }
        assertEquals(ChunkBody.FileRange(8, 2, false), sent[2].body)

        env.planner.finalize(task.id)
        val (xml, callback) = env.requests.drop(1)
        assertEquals("POST", xml.method)
        assertEquals(complete, xml.url)
        assertEquals("application/octet-stream", xml.contentType)
        assertEquals(
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>\"etag-1\"</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>\"etag-2\"</ETag></Part>" +
                "<Part><PartNumber>3</PartNumber><ETag>\"etag-3\"</ETag></Part>" +
                "</CompleteMultipartUpload>",
            xml.body,
        )
        assertEquals("GET", callback.method)
        assertEquals("$SITE/api/v4/callback/s3/$SID/$SECRET", callback.url)
        assertEquals(TransferState.COMPLETED, env.repository.task(task.id)!!.state)
        assertEquals(3, env.requests.size)
    }

    @Test
    fun s3MissingEtagFails() = runTest {
        val env = TransferEnv { r ->
            if (isCreate(r)) ok(sessionJson(StoragePolicy.TYPE_S3, 0, uploadUrls = listOf("https://s3/1"), completeUrl = "https://s3/c")) else ok()
        }
        val task = env.upload(bytes(4))
        env.planner.prepare(task.id)
        env.planner.nextRequests(task.id)
        val outcome = env.planner.onChunkDone(task.id, 0, ChunkResponse(200, etag = null))
        assertEquals(TransferErrorCode.MISSING_ETAG, (outcome as TransferOutcome.Failed).code)
    }

    @Test
    fun cosCompleteForbidsOverwriteAndCallsBack() = runTest {
        val complete = "https://cos.test/key?uploadId=u"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_COS, 0, uploadUrls = listOf("https://cos.test/p1"), completeUrl = complete))
                r.url == complete -> raw("<CompleteMultipartUploadResult/>", contentType = "application/xml")
                else -> ok()
            }
        }
        val task = env.upload(bytes(4))
        env.planner.prepare(task.id)
        env.sendAll(task.id) { ChunkResponse(200, etag = "\"e\"") }
        env.planner.finalize(task.id)
        val (xml, callback) = env.requests.drop(1)
        assertEquals("true", xml.headers["x-cos-forbid-overwrite"])
        assertTrue(xml.body.startsWith("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"e\"</ETag>"))
        assertEquals("GET", callback.method)
        assertEquals("$SITE/api/v4/callback/cos/$SID/$SECRET", callback.url)
    }

    @Test
    fun obsCompletesWithXmlAndNoClientCallback() = runTest {
        val complete = "https://obs.test/key?uploadId=u&callback=abc"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_OBS, 0, uploadUrls = listOf("https://obs.test/p1"), completeUrl = complete))
                r.url == complete -> raw("{}")
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.upload(bytes(4))
        env.planner.prepare(task.id)
        env.sendAll(task.id) { ChunkResponse(200, etag = "\"e\"") }
        env.planner.finalize(task.id)
        assertEquals(2, env.requests.size)
        assertTrue(env.requests.last().body.contains("<PartNumber>1</PartNumber>"))
    }

    @Test
    fun ossCompleteHasEmptyBodyAndHeaders() = runTest {
        val complete = "https://oss.test/key?uploadId=u&callback=x"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_OSS, 4, uploadUrls = listOf("https://oss/1", "https://oss/2"), completeUrl = complete))
                r.url == complete -> raw("{}")
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.upload(bytes(8))
        env.planner.prepare(task.id)
        val sent = env.sendAll(task.id) { ChunkResponse(200) }
        assertEquals(listOf("https://oss/1", "https://oss/2"), sent.map { it.url })
        assertTrue(sent.all { it.method == "PUT" })
        env.planner.finalize(task.id)
        val req = env.requests.last()
        assertEquals("POST", req.method)
        assertEquals("", req.body)
        assertEquals("true", req.headers["x-oss-forbid-overwrite"])
        assertEquals("yes", req.headers["x-oss-complete-all"])
        assertEquals(2, env.requests.size, "OSS 由存储方回调主站，客户端不调用回调")
    }

    @Test
    fun qiniuPartsJson() = runTest {
        val base = "https://up.qiniu.test/buckets/b/objects/k/uploads/u"
        val env = TransferEnv { r ->
            when {
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_QINIU, 4, uploadUrls = listOf(base), credential = "qtoken", mimeType = "text/plain"))
                r.url == base -> raw("""{"hash":"h","key":"k"}""")
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.upload(bytes(8))
        env.planner.prepare(task.id)
        val sent = env.sendAll(task.id) { req -> ChunkResponse(200, body = """{"etag":"q${req.index + 1}","md5":"m"}""") }
        assertEquals(listOf("$base/1", "$base/2"), sent.map { it.url })
        assertTrue(sent.all { it.method == "PUT" && it.headers["Authorization"] == "UpToken qtoken" })
        env.planner.finalize(task.id)
        val finish = env.requests.last()
        assertEquals("POST", finish.method)
        assertEquals("UpToken qtoken", finish.headers["Authorization"])
        assertEquals("application/json", finish.contentType?.substringBefore(';'))
        assertEquals(
            Json.parseToJsonElement("""{"mimeType":"text/plain","parts":[{"etag":"q1","partNumber":1},{"etag":"q2","partNumber":2}]}"""),
            Json.parseToJsonElement(finish.body),
        )
    }

    @Test
    fun upyunMultipartFieldOrder() = runTest {
        val env = TransferEnv {
            ok(sessionJson(StoragePolicy.TYPE_UPYUN, 0, uploadUrls = listOf("https://v0.api.upyun.com/bucket"), credential = "UPYUN op:sig", mimeType = "image/png", uploadPolicy = "cG9saWN5"))
        }
        val content = bytes(40)
        val task = env.upload(content, name = "p.png", mime = "image/png")
        assertEquals(1, env.planner.prepare(task.id).chunkCount)
        val req = env.planner.nextRequests(task.id).single()
        assertEquals("POST", req.method)
        assertEquals("https://v0.api.upyun.com/bucket", req.url)
        val body = assertIs<ChunkBody.Multipart>(req.body)
        assertEquals("multipart/form-data; boundary=${body.boundary}", req.headers["Content-Type"])
        assertEquals(listOf("policy", "authorization", "content-type"), body.fields.map { it.name })
        assertEquals(listOf("cG9saWN5", "UPYUN op:sig", "image/png"), body.fields.map { it.value })
        assertEquals(ChunkBody.FileRange(0, 40, false), body.file)

        val raw = env.materializer.readBody(req)
        assertEquals(body.contentLength, raw.size.toLong())
        val text = raw.decodeToString()
        val order = listOf("name=\"policy\"", "name=\"authorization\"", "name=\"content-type\"", "name=\"file\"; filename=\"p.png\"")
            .map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "字段顺序 policy → authorization → content-type → file(最后)")
        assertTrue(text.endsWith("\r\n--${body.boundary}--\r\n"))
        val fileStart = body.head().size
        assertContentEquals(content, raw.copyOfRange(fileStart, fileStart + 40))
    }

    @Test
    fun conflictThenOverwriteSendsVersionEntity() = runTest {
        var conflict = true
        val env = TransferEnv { r ->
            when {
                isCreate(r) && conflict -> envelope(ErrorCode.OBJECT_EXIST, "exists")
                isCreate(r) -> ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0))
                else -> error("unexpected ${r.url}")
            }
        }
        val task = env.upload(bytes(5))
        val e = assertFailsWith<UploadConflictException> { env.planner.prepare(task.id) }
        assertEquals(ErrorCode.OBJECT_EXIST, e.code)
        assertTrue(env.repository.task(task.id)!!.isConflict)

        conflict = false
        env.manager.retry(task.id, overwrite = true)
        env.planner.prepare(task.id)
        val body = Json.parseToJsonElement(env.requests.last().body).jsonObject
        assertEquals("version", body["entity_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun conflictThenRenameChangesTargetUri() = runTest {
        val env = TransferEnv { r ->
            if (Json.parseToJsonElement(r.body).jsonObject["uri"]!!.jsonPrimitive.content.endsWith("/a.bin")) envelope(ErrorCode.OBJECT_EXIST)
            else ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0))
        }
        val task = env.upload(bytes(5))
        assertFailsWith<UploadConflictException> { env.planner.prepare(task.id) }
        env.manager.retry(task.id, newName = "a (1).bin")
        env.planner.prepare(task.id)
        assertEquals("cloudreve://my/dir/a%20(1).bin", env.repository.task(task.id)!!.targetUri)
    }

    @Test
    fun resumableSessionIsReusedWithoutRecreating() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 4)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        env.planner.onChunkDone(task.id, env.planner.nextRequests(task.id).single().index, ChunkResponse(200, body = """{"code":0}"""))
        env.planner.nextRequests(task.id) // 分片 1 在途时进程被杀
        env.planner.pause(task.id)
        env.manager.resume(task.id)
        env.planner.prepare(task.id)
        assertEquals(1, env.requests.count(::isCreate))
        assertEquals(1, env.planner.nextRequests(task.id).single().index, "已完成的分片不重传")
    }

    @Test
    fun nonResumablePolicyRecreatesSession() = runTest {
        val env = TransferEnv { r ->
            if (isCreate(r)) ok(sessionJson(StoragePolicy.TYPE_COS, 0, uploadUrls = listOf("https://cos/1"), completeUrl = "https://cos/c")) else ok()
        }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        env.planner.pause(task.id)
        env.manager.resume(task.id)
        env.planner.prepare(task.id)
        assertEquals(2, env.requests.count(::isCreate))
        val delete = env.requests.single { it.method == "DELETE" }
        assertEquals(Json.parseToJsonElement("""{"id":"$SID","uri":"cloudreve://my/dir/a.bin"}"""), Json.parseToJsonElement(delete.body))
    }

    @Test
    fun expiredSessionIsNotResumable() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_S3, 0, uploadUrls = listOf("https://s3/1"), expires = NOW_SECONDS + 10)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        assertTrue(env.planner.isResumable(task.id))
        env.nowMs += 11_000
        assertFalse(env.planner.isResumable(task.id))
    }

    @Test
    fun cancelDeletesUploadSession() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 0)) }
        val task = env.upload(bytes(10))
        env.planner.prepare(task.id)
        env.manager.cancel(task.id)
        val delete = env.requests.last()
        assertEquals("DELETE", delete.method)
        assertEquals(createUrl, delete.url)
        assertEquals(Json.parseToJsonElement("""{"id":"$SID","uri":"cloudreve://my/dir/a.bin"}"""), Json.parseToJsonElement(delete.body))
        assertEquals(TransferState.CANCELED, env.repository.task(task.id)!!.state)
    }

    @Test
    fun encryptedChunksMatchWholeFileCtr() = runTest {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val iv = ByteArray(16) { (0xF0 + it).toByte() }
        val meta = EncryptMetadata(keyPlainText = Base64.encode(key), iv = Base64.encode(iv))
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_S3, 37, uploadUrls = (1..3).map { "https://s3/$it" }, completeUrl = "https://s3/c", encrypt = meta, concurrency = 3)) }
        val content = bytes(100)
        val task = env.upload(content)
        env.planner.prepare(task.id)
        val requests = env.planner.nextRequests(task.id)
        assertEquals(3, requests.size)
        val whole = AesCtr.encryptAt(key, iv, 0, content)
        val joined = requests.map { req ->
            assertTrue((req.body as ChunkBody.FileRange).encrypted)
            env.materializer.readBody(req)
        }.reduce(ByteArray::plus)
        assertContentEquals(whole, joined)

        val materialized = env.materializer.materialize(task.id, 1)
        assertEquals(env.materializer.pathFor(task.id, 1), materialized.path)
        assertContentEquals(whole.copyOfRange(37, 74), env.fs.files.getValue(materialized.path))
        env.materializer.discard(task.id, 1)
        assertTrue(materialized.path !in env.fs.files)
    }

    @Test
    fun localTokenComesFromAuthProviderAtGenerationTime() = runTest {
        val env = TransferEnv { ok(sessionJson(StoragePolicy.TYPE_LOCAL, 4)) }
        val task = env.upload(bytes(8))
        env.planner.prepare(task.id)
        env.token = "fresh"
        assertEquals("Bearer fresh", env.materializer.materialize(task.id, 1).request.headers["Authorization"])
    }
}
