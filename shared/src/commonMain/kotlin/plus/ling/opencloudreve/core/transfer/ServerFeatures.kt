package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.endpoints.SiteApi
import plus.ling.opencloudreve.core.presentation.auth.ServerVersion

/**
 * 传输用到的服务端能力（按账户缓存 `GET /site/ping` 的版本号，进程内有效）。
 * 版本未知（ping 失败）时视为不支持、且不缓存，下次再查。
 */
internal class ServerFeatures(private val clients: ApiClientProvider) {
    private val mutex = Mutex()
    private val versions = HashMap<String, String>()

    /** 服务端 ≥ [UPLOAD_RECOVERY_MIN_VERSION]：支持 `GET /file/upload/:id` 完成回执与创建会话的 `request_id` 重放 */
    suspend fun uploadRecovery(accountId: String): Boolean {
        val version = version(accountId) ?: return false
        return ServerVersion.isAtLeast(version, UPLOAD_RECOVERY_MIN_VERSION)
    }

    private suspend fun version(accountId: String): String? {
        mutex.withLock { versions[accountId] }?.let { return it }
        val v = try {
            SiteApi(clients.client(accountId)).ping()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        mutex.withLock { versions[accountId] = v }
        return v
    }

    companion object {
        /** 上传结果未知时可向服务端核实（会话完成回执）的最低服务端版本 */
        const val UPLOAD_RECOVERY_MIN_VERSION = "4.19.12"
    }
}
