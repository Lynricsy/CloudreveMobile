package plus.ling.opencloudreve.core.auth

import io.ktor.client.engine.HttpClientEngine
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase

/**
 * 每个账户缓存一个已鉴权的 [ApiClient]（令牌由 [SessionStore] 按账户提供），以及按站点缓存的匿名客户端。
 */
class ApiClientRegistry internal constructor(
    private val database: OpenCloudreveDatabase,
    private val sessions: SessionStore,
    private val identity: ClientIdentity,
    private val anonymousPool: AnonymousClientPool,
    private val engineFactory: () -> HttpClientEngine,
) : ApiClientProvider {
    private val clients = CopyOnWriteCache<String, ApiClient>()

    /** 安装级 `X-Cr-Client-Id` */
    val clientId: String get() = identity.clientId

    /** 账户客户端；账户不存在或会话已失效（无令牌）时抛 [SessionExpiredException] */
    @Throws(SessionExpiredException::class)
    override fun client(accountId: String): ApiClient {
        if (sessions.token(accountId) == null) throw SessionExpiredException(accountId)
        clients[accountId]?.let { return it }
        val record = database.accountQueries.selectById(accountId).executeAsOneOrNull()
            ?: throw SessionExpiredException(accountId)
        return clients.getOrPut(accountId, discard = { it.dispose() }) {
            ApiClient(record.server_url, identity.clientId, sessions.authProvider(accountId), engineFactory())
        }
    }

    /** 匿名客户端（登录前、刷新/登出、访问公开分享） */
    fun anonymous(siteUrl: String): ApiClient = anonymousPool.client(siteUrl)

    /** 移除并关闭账户客户端（登出时调用） */
    fun evict(accountId: String) {
        clients.remove(accountId)?.dispose()
    }
}
