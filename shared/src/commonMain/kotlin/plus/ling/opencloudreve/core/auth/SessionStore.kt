package plus.ling.opencloudreve.core.auth

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.AuthProvider
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.auth.model.RefreshTokenRequest
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.platform.SecretStore
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 按账户保存登录令牌（安全存储，键 `token.<accountId>`），并为每个账户提供 [AuthProvider]：
 *
 * - access token 剩余有效期不足 [REFRESH_MARGIN]（10 分钟）时先刷新；
 * - 刷新是 single-flight 的：同一账户同时只有一个进行中的刷新（Mutex + 共享 Deferred），
 *   其余调用等待同一结果；刷新完成后到达的调用通过 refresh token 是否已轮换识别并直接复用；
 * - 每次刷新都保存服务端签发的新 refresh token；
 * - 刷新返回 40020 或 401、或 refresh token 已过期：清除令牌、把账户标记为失效并发出
 *   [AuthEvent.SignedOut]（`expired = true`），之后 [AuthProvider] 返回 null；
 * - 网络错误不登出，异常原样抛给发起请求的一方。
 *
 * 主 App 与扩展共享安全存储：收到 401 或准备刷新前都会从存储重读，以便采用另一进程刚刷新的令牌。
 */
class SessionStore internal constructor(
    private val secrets: SecretStore,
    private val database: OpenCloudreveDatabase,
    private val anonymous: AnonymousClientPool,
    private val clock: () -> Instant = { Clock.System.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val sessions = CopyOnWriteCache<String, AccountSession>()
    private val providers = CopyOnWriteCache<String, AuthProvider>()
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 32)

    /** 会话事件（登录、被动/主动登出） */
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /** 当前保存的令牌（不触发刷新） */
    fun token(accountId: String): Token? {
        val s = session(accountId)
        if (!s.loaded) reload(accountId)
        return s.cached
    }

    /** 保存（覆盖）令牌 */
    fun saveToken(accountId: String, token: Token) {
        secrets.put(key(accountId), CloudreveJson.encodeToString(Token.serializer(), token).encodeToByteArray())
        session(accountId).apply {
            cached = token
            loaded = true
        }
    }

    /** 删除令牌（登出） */
    fun clearToken(accountId: String) {
        secrets.remove(key(accountId))
        session(accountId).apply {
            cached = null
            loaded = true
        }
    }

    /** 账户的 [AuthProvider]（同一账户返回同一实例） */
    fun authProvider(accountId: String): AuthProvider =
        providers.getOrPut(accountId) { AccountAuthProvider(accountId) }

    /**
     * 可用的 access token：剩余不足 10 分钟先刷新（single-flight）。
     * 未登录或会话已失效返回 null；网络错误时抛出。
     */
    @Throws(Exception::class)
    suspend fun validAccessToken(accountId: String): String? {
        val current = token(accountId) ?: return null
        if (isFresh(current)) return current.accessToken
        return refreshShared(accountId, current)?.accessToken
    }

    internal fun emit(event: AuthEvent) {
        _events.tryEmit(event)
    }

    private fun isFresh(token: Token): Boolean = token.accessExpires - clock() > REFRESH_MARGIN

    private fun session(accountId: String): AccountSession =
        sessions.getOrPut(accountId) { AccountSession() }

    /** 从安全存储重读（其它进程可能已经刷新） */
    private fun reload(accountId: String): Token? {
        val token = secrets.get(key(accountId))?.let { bytes ->
            runCatching { CloudreveJson.decodeFromString(Token.serializer(), bytes.decodeToString()) }.getOrNull()
        }
        session(accountId).apply {
            cached = token
            loaded = true
        }
        return token
    }

    /**
     * single-flight 刷新。[observed] 是调用方据以判断“需要刷新”的令牌：
     * 若存储中的 refresh token 已与之不同，说明别人刚刷新过，直接复用。
     */
    private suspend fun refreshShared(accountId: String, observed: Token): Token? {
        val s = session(accountId)
        val job: Deferred<Token?> = s.mutex.withLock {
            s.inFlight?.takeIf { it.isActive }?.let { return@withLock it }
            val latest = reload(accountId) ?: return null
            if (latest.refreshToken != observed.refreshToken && isFresh(latest)) {
                return@withLock CompletableDeferred(latest)
            }
            if (latest.refreshExpires <= clock()) {
                expire(accountId)
                return null
            }
            scope.async { performRefresh(accountId, latest) }.also { s.inFlight = it }
        }
        return job.await()
    }

    private suspend fun performRefresh(accountId: String, current: Token): Token? {
        val siteUrl = database.accountQueries.selectById(accountId).executeAsOneOrNull()?.server_url
            ?: return null // 账户已删除
        return try {
            val renewed = anonymous.client(siteUrl).call(
                HttpMethod.Post,
                "/session/token/refresh",
                Token.serializer(),
                body = CloudreveJson.encodeToJsonElement(
                    RefreshTokenRequest.serializer(),
                    RefreshTokenRequest(current.refreshToken),
                ),
                noAuth = true,
            )
            saveToken(accountId, renewed)
            renewed
        } catch (e: ApiException) {
            if (e.code == ErrorCode.CREDENTIAL_INVALID || e.code == ErrorCode.LOGIN_REQUIRED) {
                expire(accountId)
                null
            } else {
                throw e
            }
        }
    }

    /** 会话失效：清令牌、标记账户、发事件 */
    private fun expire(accountId: String) {
        clearToken(accountId)
        database.accountQueries.markSessionExpired(accountId)
        emit(AuthEvent.SignedOut(accountId, expired = true))
    }

    private inner class AccountAuthProvider(private val accountId: String) : AuthProvider {
        override suspend fun validAccessToken(): String? = this@SessionStore.validAccessToken(accountId)

        override suspend fun refreshAfterRejection(rejectedToken: String): String? {
            val latest = reload(accountId) ?: return null
            if (latest.accessToken != rejectedToken && isFresh(latest)) return latest.accessToken
            return refreshShared(accountId, latest)?.accessToken
        }
    }

    private class AccountSession {
        val mutex = Mutex()

        @Volatile
        var cached: Token? = null

        @Volatile
        var loaded = false

        /** 进行中的刷新（受 [mutex] 保护） */
        var inFlight: Deferred<Token?>? = null
    }

    companion object {
        /** 预刷新余量：与 Web 前端一致 */
        val REFRESH_MARGIN = 10.minutes

        private fun key(accountId: String) = "token.$accountId"
    }
}
