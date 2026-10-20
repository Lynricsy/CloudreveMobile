package plus.ling.opencloudreve.core.auth

import app.cash.sqldelight.Query
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.serializer
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.CloudreveException
import plus.ling.opencloudreve.core.auth.model.RefreshTokenRequest
import plus.ling.opencloudreve.core.db.AccountRecord
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.Token
import plus.ling.opencloudreve.core.model.User
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 多账户管理：增删、切换、登出。数据库变化（包括 [SessionStore] 标记失效）会同步推送到
 * [accounts] / [activeAccount]（SQLDelight 查询监听，同步触发）。
 */
class AccountRepository internal constructor(
    private val database: OpenCloudreveDatabase,
    private val sessions: SessionStore,
    private val registry: ApiClientRegistry,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val queries = database.accountQueries
    private val allQuery: Query<AccountRecord> = queries.selectAll()
    private val _accounts = MutableStateFlow(loadAll())
    private val _active = MutableStateFlow(_accounts.value.firstOrNull { it.isActive })
    private val listener = Query.Listener { publish() }

    /** 全部账户（按添加时间） */
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    /** 当前选中的账户 */
    val activeAccount: StateFlow<Account?> = _active.asStateFlow()

    /** 登录、登出、会话失效事件 */
    val authEvents: SharedFlow<AuthEvent> = sessions.events

    init {
        allQuery.addListener(listener)
    }

    fun account(id: String): Account? = queries.selectById(id).executeAsOneOrNull()?.toAccount()

    /**
     * 登录成功后写入账户与令牌（同一服务器同一用户覆盖），清除失效标记。
     * [activate] 为 true 或当前没有选中账户时设为当前账户。
     */
    @Throws(Exception::class)
    fun addOrUpdate(siteUrl: String, user: User, token: Token, activate: Boolean = true): Account {
        val normalized = SiteUrl.normalize(siteUrl)
        val id = SiteUrl.accountId(normalized, user.id)
        sessions.saveToken(id, token)
        database.transaction {
            queries.insertIfAbsent(
                id = id,
                server_url = normalized,
                user_id = user.id,
                email = user.email.orEmpty(),
                nickname = user.nickname,
                created_at = clock().toEpochMilliseconds(),
            )
            queries.updateLogin(
                server_url = normalized,
                user_id = user.id,
                email = user.email.orEmpty(),
                nickname = user.nickname,
                id = id,
            )
            if (activate || queries.selectActive().executeAsOneOrNull() == null) {
                queries.clearActive()
                queries.setActive(id)
            }
        }
        sessions.emit(AuthEvent.SignedIn(id))
        return account(id) ?: throw CloudreveException("Account $id not saved")
    }

    /** 用最新用户资料更新昵称/邮箱 */
    fun updateProfile(id: String, user: User) {
        queries.updateProfile(email = user.email.orEmpty(), nickname = user.nickname, id = id)
    }

    /** 头像修改后调用，使头像 URL 失效 */
    fun bumpAvatarVersion(id: String) {
        queries.bumpAvatarVersion(id)
    }

    /** 切换当前账户；账户不存在抛 [IllegalArgumentException] */
    @Throws(Exception::class)
    fun switchTo(id: String) {
        database.transaction {
            require(queries.selectById(id).executeAsOneOrNull() != null) { "Unknown account $id" }
            queries.clearActive()
            queries.setActive(id)
        }
    }

    /**
     * 主动登出：尽力调用 `DELETE /session/token` 撤销 refresh token（网络失败也继续），
     * 然后清除令牌与客户端缓存，删除账户行及其缓存；若它是当前账户，切到剩余账户中最早添加的一个。
     */
    @Throws(Exception::class)
    suspend fun signOut(id: String) {
        val record = queries.selectById(id).executeAsOneOrNull()
        val token = sessions.token(id)
        if (record != null && token != null) {
            try {
                registry.anonymous(record.server_url).call(
                    HttpMethod.Delete,
                    "/session/token",
                    String.serializer(),
                    body = CloudreveJson.encodeToJsonElement(
                        RefreshTokenRequest.serializer(),
                        RefreshTokenRequest(token.refreshToken),
                    ),
                    noAuth = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 撤销失败不影响本地登出：refresh token 会自然过期
            }
        }
        sessions.clearToken(id)
        registry.evict(id)
        database.transaction {
            val wasActive = record?.is_active == 1L
            queries.deleteById(id)
            database.cacheQueries.deleteListingsForAccount(id)
            database.cacheQueries.deleteSearchesForAccount(id)
            database.cacheQueries.deleteSiteConfigsFor(id)
            if (wasActive) {
                queries.selectAll().executeAsList().firstOrNull()?.let { queries.setActive(it.id) }
            }
        }
        sessions.emit(AuthEvent.SignedOut(id, expired = false))
    }

    private fun loadAll(): List<Account> = allQuery.executeAsList().map { it.toAccount() }

    private fun publish() {
        val all = loadAll()
        _accounts.value = all
        _active.value = all.firstOrNull { it.isActive }
    }
}
