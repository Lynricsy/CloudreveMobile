package plus.ling.opencloudreve.core.events

import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ApiEnvelope
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.model.ExplorerEvent
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 目录变更推送（SSE `GET /file/events?uri=`）。
 *
 * - 请求带账户 `ApiClient` 的 `X-Cr-Client-Id`（与修改请求相同，服务端据此排除自己的操作）和 Bearer token；
 * - 断线后用同一 client id 重连，指数退避 1s → 2s → … 最多 [maxBackoff]（默认 30s）；成功订阅后退避归零；
 * - 服务端返回 40019（站点关闭推送）时本 hub 永久禁用（[isDisabled]），所有订阅结束；
 *   其它业务错误（如 40001 目录不可用、403 单文件分享）结束该订阅；401 视为暂时性错误继续重连；
 * - 重连得到 `subscribed`（而非 `resumed`）表示离线期间的事件已丢失，此时发出一个
 *   `type == EVENT_RESYNC` 的合成事件，收集方应整体刷新。
 */
class EventHub internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
    private val initialBackoff: Duration = 1.seconds,
    private val maxBackoff: Duration = 30.seconds,
) {
    @Volatile
    private var disabled = false

    /** 站点关闭了事件推送（40019） */
    val isDisabled: Boolean get() = disabled

    /** 订阅目录 [uri] 的变更；收集方取消即断开 */
    fun subscribe(uri: String): Flow<ExplorerEvent> = channelFlow {
        var attempt = 0
        var everConnected = false
        while (!disabled) {
            try {
                stream(clients.client(accountId), uri) { frame ->
                    when (frame.event) {
                        SSE_SUBSCRIBED -> {
                            if (everConnected) send(ExplorerEvent(type = EVENT_RESYNC))
                            everConnected = true
                            attempt = 0
                        }
                        SSE_RESUMED -> {
                            everConnected = true
                            attempt = 0
                        }
                        SSE_EVENT -> decodeEvent(frame.data)?.let { send(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e.code) {
                    ErrorCode.FEATURE_NOT_ENABLED -> {
                        disabled = true
                        return@channelFlow
                    }
                    ErrorCode.LOGIN_REQUIRED -> Unit
                    else -> return@channelFlow
                }
            } catch (_: Exception) {
                // 网络错误/流中断：退避后重连
            }
            delay(backoff(attempt++))
        }
    }

    internal fun backoff(attempt: Int): Duration {
        var d = initialBackoff
        repeat(attempt.coerceAtMost(16)) { d *= 2 }
        return if (d > maxBackoff) maxBackoff else d
    }

    private suspend fun stream(client: ApiClient, uri: String, onFrame: suspend (SseFrame) -> Unit) {
        val token = client.accessToken()
        val statement = client.http.prepareGet(client.apiBase + EVENTS_PATH) {
            with(client) { applyClientHeaders(token) }
            header(HttpHeaders.Accept, CONTENT_TYPE_SSE)
            parameter("uri", uri)
            timeout { socketTimeoutMillis = SOCKET_TIMEOUT_MS }
        }
        statement.execute { response ->
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            if (!contentType.startsWith(CONTENT_TYPE_SSE)) {
                val text = response.bodyAsText()
                val env = try {
                    CloudreveJson.decodeFromString(ApiEnvelope.serializer(), text)
                } catch (e: Exception) {
                    throw TransportException("Unexpected response (HTTP ${response.status.value})", response.status.value, e)
                }
                ApiClient.throwIfError(env)
                throw TransportException("Event stream not established", response.status.value)
            }
            val channel = response.bodyAsChannel()
            val parser = SseParser()
            while (true) {
                val line = channel.readLine() ?: break
                parser.feed(line)?.let { onFrame(it) }
            }
            parser.finish()?.let { onFrame(it) }
        }
    }

    private fun decodeEvent(data: String): ExplorerEvent? =
        runCatching { CloudreveJson.decodeFromString(ExplorerEvent.serializer(), data) }.getOrNull()

    companion object {
        /** 合成事件：重连后离线事件已丢失，需要整体刷新 */
        const val EVENT_RESYNC = "resync"

        internal const val EVENTS_PATH = "/file/events"
        internal const val CONTENT_TYPE_SSE = "text/event-stream"
        internal const val SSE_SUBSCRIBED = "subscribed"
        internal const val SSE_RESUMED = "resumed"
        internal const val SSE_EVENT = "event"
        private const val SOCKET_TIMEOUT_MS = 75_000L
    }
}

/** 一条 SSE 消息 */
internal data class SseFrame(val event: String, val data: String)

/** 逐行 SSE 解析（`event:`/`data:` 字段、空行分发、`:` 注释；字段值去掉冒号后的一个空格） */
internal class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** 输入一行（不含换行符）；遇到空行且有内容时返回一条消息 */
    fun feed(rawLine: String): SseFrame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return finish()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** 分发缓冲中的消息（流结束时调用） */
    fun finish(): SseFrame? {
        val e = event
        val frame = if (e != null || hasData) SseFrame(e ?: "message", data.toString()) else null
        event = null
        data.clear()
        hasData = false
        return frame
    }
}
