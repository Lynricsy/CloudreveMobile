package plus.ling.opencloudreve.core.transfer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.AuthProvider
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.createTestDriver
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.EncryptMetadata
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.UploadSession
import kotlin.time.Instant

/** 内存文件系统：[sources] 为上传源，[files] 为写出的临时文件 */
class FakeFileSystem : FileSystemBridge {
    val sources = HashMap<String, ByteArray>()
    val files = HashMap<String, ByteArray>()

    @Throws(Exception::class)
    override suspend fun size(sourceRef: String): Long? = sources[sourceRef]?.size?.toLong()

    @Throws(Exception::class)
    override suspend fun readRange(sourceRef: String, offset: Long, length: Int): ByteArray {
        val data = sources.getValue(sourceRef)
        val start = offset.toInt().coerceAtMost(data.size)
        return data.copyOfRange(start, (start + length).coerceAtMost(data.size))
    }

    override fun tempDir(): String = "/tmp/ocr-test/"

    @Throws(Exception::class)
    override suspend fun writeFile(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
    }

    @Throws(Exception::class)
    override suspend fun appendFile(path: String, bytes: ByteArray) {
        files[path] = (files[path] ?: ByteArray(0)) + bytes
    }

    @Throws(Exception::class)
    override suspend fun delete(path: String) {
        files.remove(path)
    }
}

/** MockEngine 记录的请求 */
data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String, val contentType: String?)

const val SITE = "https://cloud.test"
const val ACCOUNT = "1@cloud.test"
const val SID = "sess-1"
const val SECRET = "cbsecret"
const val NOW_SECONDS = 1_700_000_000L

/** 一个测试环境：内存 DB + MockEngine 客户端 + 固定时钟 */
class TransferEnv(
    private val handler: suspend MockRequestHandleScope.(Recorded) -> HttpResponseData = { ok() },
) {
    val requests = ArrayList<Recorded>()
    var nowMs = NOW_SECONDS * 1000
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(nowMs) }
    val database = OpenCloudreveDatabase(createTestDriver())
    val repository = TransferRepository(database, clock, Dispatchers.Unconfined)
    val fs = FakeFileSystem()
    var token: String? = "tok"

    val client = ApiClient(
        siteUrl = SITE,
        clientId = "client-1",
        auth = object : AuthProvider {
            override suspend fun validAccessToken(): String? = token
            override suspend fun refreshAfterRejection(rejectedToken: String): String? = null
        },
        engine = MockEngine { data: HttpRequestData ->
            val rec = Recorded(
                method = data.method.value,
                url = data.url.toString(),
                headers = data.headers.entries().associate { it.key to it.value.joinToString(",") },
                body = data.body.toByteArray().decodeToString(),
                contentType = data.body.contentType?.toString(),
            )
            requests += rec
            handler(rec)
        },
    )
    val clients = object : ApiClientProvider {
        @Throws(SessionExpiredException::class)
        override fun client(accountId: String): ApiClient = client
    }
    val planner = UploadPlanner(repository, clients, fs, clock)
    val downloads = DownloadPlanner(repository, clients, clock)
    val materializer = ChunkMaterializer(planner, fs, repository, blockSize = 32)
    val manager = TransferManager(repository, planner, downloads, TransferExecutorHolder())

    /** 新建上传任务（源内容为 [content]） */
    suspend fun upload(content: ByteArray, name: String = "a.bin", mime: String? = "application/x-test"): TransferItem {
        val ref = "src://$name"
        fs.sources[ref] = content
        return repository.createUpload(ACCOUNT, UploadSource(ref, name, content.size.toLong(), 1234L, mime), "cloudreve://my/dir")
    }
}

fun MockRequestHandleScope.ok(data: String = "null") = respond("""{"code":0,"msg":"","data":$data}""")

fun MockRequestHandleScope.envelope(code: Int, msg: String = "err") = respond("""{"code":$code,"msg":"$msg"}""")

fun MockRequestHandleScope.raw(body: String, status: Int = 200, contentType: String = "application/json") =
    respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", contentType))

/** 构造上传会话 JSON（作为 `PUT /file/upload` 的 data） */
fun sessionJson(
    type: String,
    chunkSize: Long,
    uploadUrls: List<String>? = null,
    completeUrl: String? = null,
    credential: String? = null,
    concurrency: Int? = null,
    relay: Boolean = false,
    encrypt: EncryptMetadata? = null,
    mimeType: String? = null,
    uploadPolicy: String? = null,
    expires: Long = NOW_SECONDS + 86_400,
): String = CloudreveJson.encodeToString(
    UploadSession.serializer(),
    UploadSession(
        sessionId = SID,
        chunkSize = chunkSize,
        expires = expires,
        uploadUrls = uploadUrls,
        credential = credential,
        completeUrl = completeUrl,
        storagePolicy = StoragePolicy(id = "p1", type = type, relay = relay, chunkConcurrency = concurrency),
        uri = "cloudreve://my/dir/a.bin",
        callbackSecret = SECRET,
        mimeType = mimeType,
        uploadPolicy = uploadPolicy,
        encryptMetadata = encrypt,
    ),
)

/** 测试数据：0,1,2,…（取模 251） */
fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }
