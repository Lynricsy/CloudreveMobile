package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import plus.ling.opencloudreve.core.api.TransportException

/**
 * 指数退避：第 n 次失败（n 从 1 起）后等待 `baseDelayMillis * 2^(n-1)`，共 [maxRetries] 次，之后判定失败。
 * 默认 1s / 2s / 4s，与 Web `chunk.ts` 一致。
 */
data class RetryPolicy(val maxRetries: Int = 3, val baseDelayMillis: Long = 1_000) {
    /** 第 [attempt] 次失败后的等待时间；超过 [maxRetries] 返回 null（不再重试） */
    fun delayFor(attempt: Int): Long? =
        if (attempt < 1 || attempt > maxRetries) null else baseDelayMillis shl (attempt - 1)

    /** 对 [TransportException]（网络错误）按退避重试 [block]；其它异常直接抛出 */
    internal suspend fun <T> retryTransport(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                attempt++
                val wait = delayFor(attempt) ?: throw e
                delay(wait)
            }
        }
    }

    companion object {
        val DEFAULT = RetryPolicy()
    }
}
