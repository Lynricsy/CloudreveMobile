package plus.ling.opencloudreve.core.files

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.AuthProvider
import plus.ling.opencloudreve.core.api.MockServer
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.api.json
import plus.ling.opencloudreve.core.createTestDriver
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import kotlin.time.Instant

const val ACCOUNT = "1@cloud.example.com"

/** 数据层测试环境：MockEngine 客户端 + 内存 DB + 可控时钟 */
class DataEnv(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val server = MockServer(handler)
    var nowMs = 1_700_000_000_000L
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(nowMs) }
    val client: ApiClient = server.client(
        object : AuthProvider {
            override suspend fun validAccessToken(): String? = "tok"
            override suspend fun refreshAfterRejection(rejectedToken: String): String? = null
        },
    )
    val clients = object : ApiClientProvider {
        @Throws(SessionExpiredException::class)
        override fun client(accountId: String): ApiClient = client
    }
    val database = OpenCloudreveDatabase(createTestDriver())
    val files = FileRepository(ACCOUNT, clients, database, Dispatchers.Unconfined, clock)
    val site = SiteConfigRepository(clients, { client }, database, Dispatchers.Unconfined, clock)
}

/** 成功信封 */
fun MockRequestHandleScope.okData(data: String = "null"): HttpResponseData = json("""{"code":0,"msg":"","data":$data}""")

/** 错误信封 */
fun MockRequestHandleScope.err(code: Int, msg: String = "err", data: String? = null): HttpResponseData =
    json(if (data == null) """{"code":$code,"msg":"$msg"}""" else """{"code":$code,"msg":"$msg","data":$data}""")

/** 构造一个文件 JSON */
fun fileJson(name: String, dir: String = "cloudreve://my", folder: Boolean = false, metadata: String? = null): String =
    """{"type":${if (folder) 1 else 0},"id":"id-$name","name":"$name","size":1,"path":"$dir/$name",""" +
        """"capability":"/f+3","owned":true${if (metadata != null) ",\"metadata\":$metadata" else ""}}"""
