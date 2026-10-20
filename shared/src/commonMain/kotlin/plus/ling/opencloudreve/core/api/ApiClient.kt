package plus.ling.opencloudreve.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

/**
 * Cloudreve v4 API 客户端（一个账户一个实例）。
 *
 * - [siteUrl]：站点根地址，例如 `https://cloud.example.com`（不含 `/api/v4`、无尾部 `/`）
 * - [clientId]：安装级 UUID，作为 `X-Cr-Client-Id` 发送（SSE 排除自身事件依赖它）
 * - [auth]：为空表示匿名客户端（登录前、浏览公开分享）
 *
 * 行为：
 * - 解析信封，`code != 0` 抛 [ApiException]（203 也抛，调用方按 `code == 203` 读取 `data`）；
 *   40073 抛 [LockConflictException]；40081 的 `aggregated_error` 放在 [ApiException.aggregated]。
 * - 收到 401 且请求带了 token：调用 [AuthProvider.refreshAfterRejection] 后重放一次。
 */
class ApiClient(
    val siteUrl: String,
    val clientId: String,
    private val auth: AuthProvider?,
    engine: HttpClientEngine = defaultHttpEngine(),
) {
    val apiBase: String = siteUrl.trimEnd('/') + API_PREFIX

    /** 底层 Ktor 客户端；也供非信封请求（存储直传、`/file/content`、SSE）复用 */
    val http: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 60_000
        }
    }

    /** 当前可用 access token（给需要自行发请求的模块使用，如本机分片上传、SSE） */
    suspend fun accessToken(): String? = auth?.validAccessToken()

    /** 发送请求并返回原始信封（不按 code 抛错，只处理 401 刷新重放） */
    suspend fun envelope(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): ApiEnvelope {
        val token = if (noAuth) null else auth?.validAccessToken()
        val first = send(method, path, query, body, token, headers)
        if (first.code == ErrorCode.LOGIN_REQUIRED && token != null && auth != null) {
            val renewed = auth.refreshAfterRejection(token) ?: return first
            return send(method, path, query, body, renewed, headers)
        }
        return first
    }

    /** 发送请求，`code == 0` 时把 `data` 解码为 [T]（`Unit` 忽略 data） */
    suspend fun <T> call(
        method: HttpMethod,
        path: String,
        resultSerializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val env = envelope(method, path, query, body, noAuth, headers)
        throwIfError(env)
        return decodeData(env.data, resultSerializer)
    }

    suspend inline fun <reified T> get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = call(HttpMethod.Get, path, serializer<T>(), query, null, noAuth, headers)

    suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
        query: Map<String, Any?> = emptyMap(),
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = call(HttpMethod.Post, path, serializer<T>(), query, encodeBody(body), noAuth, headers)

    suspend inline fun <reified T, reified B> put(
        path: String,
        body: B,
        query: Map<String, Any?> = emptyMap(),
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = call(HttpMethod.Put, path, serializer<T>(), query, encodeBody(body), noAuth, headers)

    suspend inline fun <reified T, reified B> patch(
        path: String,
        body: B,
        query: Map<String, Any?> = emptyMap(),
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = call(HttpMethod.Patch, path, serializer<T>(), query, encodeBody(body), noAuth, headers)

    suspend inline fun <reified T, reified B> delete(
        path: String,
        body: B,
        query: Map<String, Any?> = emptyMap(),
        noAuth: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = call(HttpMethod.Delete, path, serializer<T>(), query, encodeBody(body), noAuth, headers)

    suspend inline fun <reified T> deleteNoBody(
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): T = call(HttpMethod.Delete, path, serializer<T>(), query, null)

    /** 给请求统一加上客户端头 */
    fun HttpRequestBuilder.applyClientHeaders(token: String?) {
        header(HEADER_CLIENT_ID, clientId)
        header(HEADER_CORRELATION_ID, Uuid.random().toString())
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    fun close() = http.close()

    @PublishedApi
    internal inline fun <reified B> encodeBody(body: B): JsonElement? = when (body) {
        null -> null
        is JsonElement -> body
        is Unit -> JsonObject(emptyMap())
        else -> CloudreveJson.encodeToJsonElement(serializer<B>(), body)
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?>,
        body: JsonElement?,
        token: String?,
        headers: Map<String, String>,
    ): ApiEnvelope {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else apiBase + path
        val response = try {
            http.request(url) {
                this.method = method
                applyClientHeaders(token)
                headers.forEach { (k, v) -> header(k, v) }
                appendQuery(query)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(CloudreveJson.encodeToString(JsonElement.serializer(), body))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: e::class.simpleName ?: "network error", cause = e)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "read error", cause = e)
        }
        return try {
            CloudreveJson.decodeFromString(ApiEnvelope.serializer(), text)
        } catch (e: Exception) {
            throw TransportException("Unexpected response (HTTP ${response.status.value})", response.status.value, e)
        }
    }

    companion object {
        const val API_PREFIX = "/api/v4"
        const val HEADER_CLIENT_ID = "X-Cr-Client-Id"
        const val HEADER_CORRELATION_ID = "X-Cr-Correlation-Id"
        const val HEADER_CONTEXT_HINT = "X-Cr-Context-Hint"

        /** 把 query 写入请求：null 跳过，Iterable 展开为同名多值，Boolean 输出 true/false */
        fun HttpRequestBuilder.appendQuery(query: Map<String, Any?>) {
            query.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is Iterable<*> -> v.forEach { item -> if (item != null) parameter(k, item.toString()) }
                    else -> parameter(k, v.toString())
                }
            }
        }

        /** `code != 0` 时抛出对应异常 */
        fun throwIfError(env: ApiEnvelope) {
            when (env.code) {
                0 -> return
                ErrorCode.LOCK_CONFLICT -> {
                    val conflicts = env.data?.let {
                        runCatching {
                            CloudreveJson.decodeFromJsonElement(
                                kotlinx.serialization.builtins.ListSerializer(LockConflict.serializer()), it,
                            )
                        }.getOrNull()
                    } ?: emptyList()
                    throw LockConflictException(env.msg, env.data, conflicts)
                }
                else -> throw ApiException(env.code, env.msg, env.data, env.aggregatedError, env.correlationId)
            }
        }

        /** 解码 data：Unit 忽略；缺失时按 JSON null 解码（可空类型得到 null） */
        @Suppress("UNCHECKED_CAST")
        fun <T> decodeData(data: JsonElement?, serializer: KSerializer<T>): T {
            if (serializer == Unit.serializer()) return Unit as T
            return CloudreveJson.decodeFromJsonElement(serializer, data ?: JsonNull)
        }
    }
}
