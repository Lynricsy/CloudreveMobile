package plus.ling.opencloudreve.core.presentation

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import plus.ling.opencloudreve.core.files.ACCOUNT
import plus.ling.opencloudreve.core.files.DataEnv
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.share.ShareRepository
import plus.ling.opencloudreve.core.workflow.WorkflowRepository
import plus.ling.opencloudreve.core.presentation.viewer.ViewerEnvironment

/** 真实时间等待条件成立（VM 运行在 Unconfined 主调度器上，HTTP 在 MockEngine 线程完成） */
suspend fun eventually(timeoutMs: Long = 5_000, block: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(timeoutMs) {
        while (!block()) delay(5)
    }
}

/** ViewModel 测试环境：[DataEnv] + 分享/任务/用户仓库 */
class VmEnv(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val data = DataEnv(handler)
    val server get() = data.server
    val files get() = data.files
    val site get() = data.site
    val shares = ShareRepository(ACCOUNT, data.clients)
    val workflows = WorkflowRepository(ACCOUNT, data.clients)
    val userSettings = UserSettingsRepository(ACCOUNT, data.clients) { _, _ -> }
    val viewer = ViewerEnvironment(files, site, userSettings, data.clients, data.clock)
}

/** 用户 JSON；[permissionB64] 为用户组权限位集合（base64） */
fun userJson(id: String = "u1", permissionB64: String = "", batch: Int = 0): String =
    """{"id":"$id","email":"$id@x","nickname":"$id","group":{"id":"g","name":"G","permission":"$permissionB64","direct_link_batch_size":$batch}}"""
