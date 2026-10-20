package plus.ling.opencloudreve.core.auth

import io.ktor.client.engine.HttpClientEngine
import plus.ling.opencloudreve.core.api.ApiClient
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** 线程安全的写时复制缓存（读多写少：每账户/站点一个客户端） */
@OptIn(ExperimentalAtomicApi::class)
internal class CopyOnWriteCache<K, V : Any> {
    private val ref = AtomicReference(emptyMap<K, V>())

    operator fun get(key: K): V? = ref.load()[key]

    /** 不存在时创建；并发创建时只保留一个，落选者交给 [discard] */
    fun getOrPut(key: K, discard: (V) -> Unit = {}, create: () -> V): V {
        ref.load()[key]?.let { return it }
        val created = create()
        while (true) {
            val current = ref.load()
            current[key]?.let {
                discard(created)
                return it
            }
            if (ref.compareAndSet(current, current + (key to created))) return created
        }
    }

    fun remove(key: K): V? {
        while (true) {
            val current = ref.load()
            val value = current[key] ?: return null
            if (ref.compareAndSet(current, current - key)) return value
        }
    }
}

/** 匿名（无 token）客户端池：登录前请求、刷新 token、登出、浏览公开分享。按规范化站点地址缓存 */
internal class AnonymousClientPool(
    private val identity: ClientIdentity,
    private val engineFactory: () -> HttpClientEngine,
) {
    private val clients = CopyOnWriteCache<String, ApiClient>()

    fun client(siteUrl: String): ApiClient {
        val normalized = SiteUrl.normalize(siteUrl)
        return clients.getOrPut(normalized, discard = { it.dispose() }) {
            ApiClient(normalized, identity.clientId, auth = null, engine = engineFactory())
        }
    }
}

/** 关闭客户端及其引擎（引擎由工厂为每个客户端单独创建，HttpClient 不会代为关闭） */
internal fun ApiClient.dispose() {
    close()
    http.engine.close()
}
