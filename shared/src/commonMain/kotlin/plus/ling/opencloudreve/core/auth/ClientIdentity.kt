package plus.ling.opencloudreve.core.auth

import plus.ling.opencloudreve.core.platform.SecretStore
import kotlin.uuid.Uuid

/**
 * 安装级客户端标识（`X-Cr-Client-Id`）。首次读取时生成 UUID 并写入安全存储，
 * 之后主 App 与扩展（共享 Keychain/存储）读到同一个值，所有修改请求与 SSE 订阅都带它，
 * 服务端据此过滤掉本设备自己产生的事件。
 */
class ClientIdentity(private val store: SecretStore) {
    val clientId: String by lazy { loadOrCreate() }

    private fun loadOrCreate(): String {
        store.get(KEY)?.decodeToString()?.takeIf { it.isNotBlank() }?.let { return it }
        val id = Uuid.random().toString()
        store.put(KEY, id.encodeToByteArray())
        return id
    }

    private companion object {
        const val KEY = "client_id"
    }
}
