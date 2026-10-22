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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 * - 网络错误不登出，异常原样抛给发起请求的一方；
 * - 会话代际：[saveToken]/[clearToken] 开启新代际，刷新结果在提交前核对代际，
 *   刷新期间重新登录或登出时丢弃旧结果（不覆盖新令牌、不注销新会话）。
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

    /** 保存（覆盖）令牌：开启新的会话代际，进行中的旧刷新结果随之作废 */
    fun saveToken(accountId: String, token: Token) {
        val s = session(accountId)
        s.commit.withLock {
            s.generation++
            writeToken(accountId, s, token)
        }
    }

    /** 删除令牌（登出）：同样开启新代际，晚到的旧刷新不会写回令牌或注销 */
    fun clearToken(accountId: String) {
        val s = session(accountId)
        s.commit.withLock {
            s.generation++
            writeToken(accountId, s, null)
        }
    }

    /** 写入存储与缓存；调用方持有 [AccountSession.commit] */
    private fun writeToken(accountId: String, s: AccountSession, token: Token?) {
        if (token == null) {
            secrets.remove(key(accountId))
        } else {
            secrets.put(key(accountId), CloudreveJson.encodeToString(Token.serializer(), token).encodeToByteArray())
        }
        s.cached = token
        s.loaded = true
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
    private fun reload(accountId: String): Token? = reloadWithGeneration(accountId).first

    /** 重读令牌并取得与之对应的会话代际（在提交锁内读取，避免与并发登录交错） */
    private fun reloadWithGeneration(accountId: String): Pair<Token?, Long> {
        val s = session(accountId)
        return s.commit.withLock {
            val token = secrets.get(key(accountId))?.let { bytes ->
                runCatching { CloudreveJson.decodeFromString(Token.serializer(), bytes.decodeToString()) }.getOrNull()
            }
            s.cached = token
            s.loaded = true
            token to s.generation
        }
    }

    /**
     * single-flight 刷新。[observed] 是调用方据以判断“需要刷新”的令牌：
     * 若存储中的 refresh token 已与之不同，说明别人刚刷新过，直接复用。
     * 进行中的刷新只给同一会话代际复用；重新登录/登出后发起的调用不会加入旧刷新。
     */
    private suspend fun refreshShared(accountId: String, observed: Token): Token? {
        val s = session(accountId)
        val job: Deferred<Token?> = s.mutex.withLock {
            s.inFlight?.takeIf { it.job.isActive && it.generation == s.generation }?.let { return@withLock it.job }
            val (latest, generation) = reloadWithGeneration(accountId)
            if (latest == null) return null
            if (latest.refreshToken != observed.refreshToken && isFresh(latest)) {
                return@withLock CompletableDeferred(latest)
            }
            if (latest.refreshExpires <= clock()) {
                return if (expire(accountId, generation)) null else s.cached
            }
            scope.async { performRefresh(accountId, latest, generation) }.also { s.inFlight = InFlight(generation, it) }
        }
        return job.await()
    }

    /**
     * 刷新 [current]（属于会话代际 [generation]）。提交结果前在提交锁内核对代际：
     * 期间重新登录或登出则丢弃本次结果（不写回、不注销），返回当前保存的令牌。
     */
    private suspend fun performRefresh(accountId: String, current: Token, generation: Long): Token? {
        val s = session(accountId)
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
            s.commit.withLock {
                if (s.generation != generation) return@withLock s.cached
                writeToken(accountId, s, renewed)
                renewed
            }
        } catch (e: ApiException) {
            if (e.code == ErrorCode.CREDENTIAL_INVALID || e.code == ErrorCode.LOGIN_REQUIRED) {
                if (expire(accountId, generation)) null else s.cached
            } else {
                throw e
            }
        }
    }

    /** 会话失效：仍是代际 [generation] 时清令牌、标记账户、发事件；已被新登录/登出取代则不动并返回 false */
    private fun expire(accountId: String, generation: Long): Boolean {
        val s = session(accountId)
        val expired = s.commit.withLock {
            if (s.generation != generation) return@withLock false
            s.generation++
            writeToken(accountId, s, null)
            database.accountQueries.markSessionExpired(accountId)
            true
        }
        if (expired) emit(AuthEvent.SignedOut(accountId, expired = true))
        return expired
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

        /** 提交锁：令牌写入与代际变更的同步边界（非挂起，登录/登出可在任意线程调用） */
        val commit = CommitLock()

        /** 会话代际：每次登录写入、登出或失效时递增（受 [commit] 保护） */
        @Volatile
        var generation = 0L

        @Volatile
        var cached: Token? = null

        @Volatile
        var loaded = false

        /** 进行中的刷新（受 [mutex] 保护） */
        var inFlight: InFlight? = null
    }

    private class InFlight(val generation: Long, val job: Deferred<Token?>)

    /** 极短临界区（写安全存储/标记账户）用的自旋锁；只在登录与刷新提交恰好同时发生时才会竞争 */
    @OptIn(ExperimentalAtomicApi::class)
    private class CommitLock {
        private val held = AtomicInt(0)

        fun <T> withLock(block: () -> T): T {
            while (!held.compareAndSet(0, 1)) {
                // 自旋等待对方完成写入
            }
            try {
                return block()
            } finally {
                held.store(0)
            }
        }
    }

    companion object {
        /** 预刷新余量：与 Web 前端一致 */
        val REFRESH_MARGIN = 10.minutes

        private fun key(accountId: String) = "token.$accountId"
    }
}
