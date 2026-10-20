package plus.ling.opencloudreve.core.site

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.api.endpoints.SiteApi
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.SiteBasic
import plus.ling.opencloudreve.core.model.SiteEmojis
import plus.ling.opencloudreve.core.model.SiteExplorer
import plus.ling.opencloudreve.core.model.SiteLogin
import plus.ling.opencloudreve.core.model.SiteSection
import plus.ling.opencloudreve.core.model.SiteThumb
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * 站点配置（`GET /site/config/:section`）：内存 + `SiteConfigCache` 两级缓存。
 *
 * - 登录前按站点地址（`site*` 方法，匿名客户端），登录后按账户 id（已鉴权客户端，`basic` 带当前用户）。
 * - 读取顺序：内存命中直接返回（除非 `forceRefresh`）；否则请求网络并写回两级缓存；
 *   **网络失败**（[TransportException]）时退回 DB 缓存，DB 也没有才抛出。
 * - `cached*` 只读缓存不发请求（扩展进程冷启动、离线占位）。
 */
@OptIn(ExperimentalAtomicApi::class)
class SiteConfigRepository internal constructor(
    private val clients: ApiClientProvider,
    private val anonymousClient: (String) -> ApiClient,
    private val database: OpenCloudreveDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> kotlin.time.Instant = { Clock.System.now() },
) {
    private val memory = AtomicReference(emptyMap<String, JsonObject>())

    // ---- 登录前（按站点） ----

    @Throws(Exception::class)
    suspend fun siteBasic(siteUrl: String, forceRefresh: Boolean = false): SiteBasic =
        SiteApi.decodeBasic(section(siteUrl, SiteSection.BASIC, forceRefresh) { anonymousClient(siteUrl) })

    @Throws(Exception::class)
    suspend fun siteLogin(siteUrl: String, forceRefresh: Boolean = false): SiteLogin =
        SiteApi.decodeLogin(section(siteUrl, SiteSection.LOGIN, forceRefresh) { anonymousClient(siteUrl) })

    /** `GET /site/ping`（不缓存）：纯版本号字符串 */
    @Throws(Exception::class)
    suspend fun ping(siteUrl: String): String = SiteApi(anonymousClient(siteUrl)).ping()

    // ---- 登录后（按账户） ----

    @Throws(Exception::class)
    suspend fun basic(accountId: String, forceRefresh: Boolean = false): SiteBasic =
        SiteApi.decodeBasic(section(accountId, SiteSection.BASIC, forceRefresh) { clients.client(accountId) })

    @Throws(Exception::class)
    suspend fun login(accountId: String, forceRefresh: Boolean = false): SiteLogin =
        SiteApi.decodeLogin(section(accountId, SiteSection.LOGIN, forceRefresh) { clients.client(accountId) })

    @Throws(Exception::class)
    suspend fun explorer(accountId: String, forceRefresh: Boolean = false): SiteExplorer =
        SiteApi.decodeExplorer(section(accountId, SiteSection.EXPLORER, forceRefresh) { clients.client(accountId) })

    @Throws(Exception::class)
    suspend fun thumb(accountId: String, forceRefresh: Boolean = false): SiteThumb =
        SiteApi.decodeThumb(section(accountId, SiteSection.THUMB, forceRefresh) { clients.client(accountId) })

    @Throws(Exception::class)
    suspend fun emojis(accountId: String, forceRefresh: Boolean = false): SiteEmojis =
        SiteApi.decodeEmojis(section(accountId, SiteSection.EMOJIS, forceRefresh) { clients.client(accountId) })

    /** 登录后一次性预取 basic/explorer/thumb/emojis（忽略单项失败） */
    @Throws(Exception::class)
    suspend fun prefetch(accountId: String) {
        for (s in listOf(SiteSection.BASIC, SiteSection.EXPLORER, SiteSection.THUMB, SiteSection.EMOJIS)) {
            try {
                section(accountId, s, forceRefresh = true) { clients.client(accountId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单项失败不影响其它 section；之后按需重试
            }
        }
    }

    // ---- 只读缓存 ----

    fun cachedExplorer(accountId: String): SiteExplorer? = cached(accountId, SiteSection.EXPLORER)?.let(SiteApi::decodeExplorer)
    fun cachedBasic(accountOrSite: String): SiteBasic? = cached(accountOrSite, SiteSection.BASIC)?.let(SiteApi::decodeBasic)
    fun cachedThumb(accountId: String): SiteThumb? = cached(accountId, SiteSection.THUMB)?.let(SiteApi::decodeThumb)
    fun cachedEmojis(accountId: String): SiteEmojis? = cached(accountId, SiteSection.EMOJIS)?.let(SiteApi::decodeEmojis)

    /** 清除某账户/站点的两级缓存（登出时 AccountRepository 已删 DB 行，这里清内存） */
    fun clear(accountOrSite: String) {
        val prefix = "$accountOrSite|"
        while (true) {
            val cur = memory.load()
            val next = cur.filterKeys { !it.startsWith(prefix) }
            if (memory.compareAndSet(cur, next)) return
        }
    }

    private fun cached(owner: String, section: String): JsonObject? {
        memory.load()[key(owner, section)]?.let { return it }
        val row = database.cacheQueries.selectSiteConfig(owner, section).executeAsOneOrNull() ?: return null
        val json = runCatching { CloudreveJson.decodeFromString(JsonObject.serializer(), row.json) }.getOrNull() ?: return null
        put(owner, section, json)
        return json
    }

    private suspend fun section(
        owner: String,
        section: String,
        forceRefresh: Boolean,
        client: () -> ApiClient,
    ): JsonObject {
        if (!forceRefresh) memory.load()[key(owner, section)]?.let { return it }
        val fresh = try {
            SiteApi(client()).config(section)
        } catch (e: TransportException) {
            return withContext(ioDispatcher) { cached(owner, section) } ?: throw e
        }
        put(owner, section, fresh)
        withContext(ioDispatcher) {
            database.cacheQueries.putSiteConfig(
                owner,
                section,
                CloudreveJson.encodeToString(JsonObject.serializer(), fresh),
                clock().toEpochMilliseconds(),
            )
        }
        return fresh
    }

    private fun put(owner: String, section: String, json: JsonObject) {
        val k = key(owner, section)
        while (true) {
            val cur = memory.load()
            if (memory.compareAndSet(cur, cur + (k to json))) return
        }
    }

    private fun key(owner: String, section: String) = "$owner|$section"
}
