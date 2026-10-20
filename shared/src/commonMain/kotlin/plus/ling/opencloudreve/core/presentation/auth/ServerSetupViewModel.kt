package plus.ling.opencloudreve.core.presentation.auth

import kotlinx.coroutines.Job
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.SiteUrl
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError

/** 服务器地址页状态 */
data class ServerSetupUiState(
    /** 输入框原文 */
    val url: String = "",
    val checking: Boolean = false,
    val error: UiError? = null,
) {
    /** 输入非空且不在检查中时可提交 */
    val canSubmit: Boolean get() = url.isNotBlank() && !checking
}

sealed interface ServerSetupIntent {
    data class UrlChanged(val value: String) : ServerSetupIntent

    /** 规范化地址（补 `https://`、去尾 `/`）后探测服务器 */
    data object Submit : ServerSetupIntent

    data object DismissError : ServerSetupIntent
}

sealed interface ServerSetupEffect {
    /** 探测成功：进入该站点的登录页 */
    data class Proceed(val server: ServerInfo) : ServerSetupEffect
}

/**
 * 服务器地址页：`GET /site/ping`（版本 ≥ 4.19.11 标记增强功能）+ `GET /site/config/basic`（标题、Logo）。
 */
class ServerSetupViewModel(
    private val registry: ApiClientRegistry,
) : CoreViewModel<ServerSetupUiState, ServerSetupIntent, ServerSetupEffect>(ServerSetupUiState()) {

    private var job: Job? = null

    override fun on(intent: ServerSetupIntent) {
        when (intent) {
            is ServerSetupIntent.UrlChanged -> updateState { it.copy(url = intent.value, error = null) }
            ServerSetupIntent.Submit -> submit()
            ServerSetupIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun submit() {
        val raw = currentState.url
        if (raw.isBlank() || job?.isActive == true) return
        val siteUrl = SiteUrl.normalize(raw)
        if (!looksLikeUrl(siteUrl)) {
            updateState { it.copy(error = UiError(ErrorKind.Validation)) }
            return
        }
        updateState { it.copy(url = siteUrl, checking = true, error = null) }
        job = launchCatching(onError = { e -> updateState { it.copy(checking = false, error = e) } }) {
            val info = probeServer(registry, siteUrl)
            updateState { it.copy(checking = false) }
            sendEffect(ServerSetupEffect.Proceed(info))
        }
    }

    /** 粗略校验：协议后至少有主机名，且不含空白 */
    private fun looksLikeUrl(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/')
        return host.isNotEmpty() && url.none { it.isWhitespace() }
    }
}
