package plus.ling.opencloudreve.core.presentation.viewer

import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.UriCodec
import plus.ling.opencloudreve.core.model.Viewer
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.handoff.attempt
import plus.ling.opencloudreve.core.presentation.toUiError
import kotlin.io.encoding.Base64

/** 网页预览器的打开方式 */
sealed interface WebViewerTarget {
    val url: String

    /** 应用内 WebView 加载；[viaHandoff] 为 true 时是一次性交接地址（不可重复加载，重载请投递 [ViewerWebIntent.Reload]） */
    data class Embedded(override val url: String, val viaHandoff: Boolean) : WebViewerTarget

    /** 外部浏览器打开（custom 预览器 `props.openInNew` 为真） */
    data class ExternalBrowser(override val url: String) : WebViewerTarget

    /** 非 http(s) scheme（如 `nplayer://`），交给系统按 scheme 打开 */
    data class ExternalApp(override val url: String) : WebViewerTarget
}

data class ViewerWebState(
    val file: FileItem,
    val viewer: Viewer? = null,
    val target: WebViewerTarget? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
    /** 内置/WOPI 预览器依赖会话交接；服务端过低时为 [HandoffAvailability.Unsupported]（提示“需要服务端 ≥ 4.19.11”） */
    val handoff: HandoffAvailability = HandoffAvailability.Checking,
)

sealed interface ViewerWebIntent {
    /** 重新生成地址（交接码一次性，重载页面必须重新生成） */
    data object Reload : ViewerWebIntent
}

/**
 * 网页预览器：内置但移动端无原生实现的（epub、drawio、excalidraw、photopea）与所有 wopi 预览器
 * 通过会话交接打开网页文件管理器并自动打开该文件（`/home?path=&open=&viewer=[&version=]`）；
 * custom 预览器按模板替换变量，按 scheme 与 `openInNew` 决定打开方式。
 */
class ViewerWebViewModel(
    private val params: ViewerParams,
    /** 平台当前是否深色模式（custom 预览器的 `{$theme}`/`{$dark}`） */
    private val darkMode: Boolean,
    private val env: ViewerEnvironment,
    private val handoff: WebHandoff,
) : CoreViewModel<ViewerWebState, ViewerWebIntent, Nothing>(ViewerWebState(params.file)) {

    init {
        prepare()
    }

    override fun on(intent: ViewerWebIntent) {
        when (intent) {
            ViewerWebIntent.Reload -> prepare()
        }
    }

    private fun prepare() {
        updateState { it.copy(loading = true, error = null, target = null) }
        scope.launch {
            try {
                val viewerId = params.viewerId ?: throw ApiException(ErrorCode.PARAM, "Missing viewer id")
                val viewer = env.resolver(params.accountId)?.viewer(viewerId)
                    ?: throw ApiException(ErrorCode.NOT_FOUND, "Unknown viewer $viewerId")
                updateState { it.copy(viewer = viewer) }
                if (viewer.type == Viewer.TYPE_CUSTOM) {
                    val target = customTarget(viewer)
                    updateState { it.copy(loading = false, target = target) }
                } else {
                    val dir = params.file.uri()?.parent()?.toString()
                        ?: throw ApiException(ErrorCode.NOT_FOUND, "File has no parent")
                    val r = handoff.attempt(
                        params.accountId,
                        WebHandoff.viewerRedirect(dir, params.file.id, viewer.id, params.version),
                    )
                    updateState {
                        it.copy(
                            loading = false,
                            handoff = r.availability ?: it.handoff,
                            target = r.url?.let { url -> WebViewerTarget.Embedded(url, viaHandoff = true) },
                            error = r.error,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    private suspend fun customTarget(viewer: Viewer): WebViewerTarget {
        val template = viewer.url?.takeIf { it.isNotBlank() } ?: throw ApiException(ErrorCode.PARAM, "Viewer URL not set")
        val res = env.files.fileUrls(listOf(params.file.linkedUri), entity = params.version, usePrimarySiteUrl = true, contextHint = params.contextHint)
        val src = res.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() } ?: throw ApiException(ErrorCode.NOT_FOUND, "Empty file url")
        val user = runCatching { env.userSettings.me() }.getOrNull()
        val url = fillTemplate(
            template,
            mapOf(
                "src" to UriCodec.encodeComponent(src),
                "src_raw" to src,
                "src_raw_base64" to Base64.encode(src.encodeToByteArray()),
                "name" to UriCodec.encodeComponent(params.file.name),
                "version" to params.version.orEmpty(),
                "id" to params.file.id,
                "user_id" to user?.id.orEmpty(),
                "user_display_name" to UriCodec.encodeComponent(user?.nickname.orEmpty()),
                "theme" to if (darkMode) "dark" else "light",
                "dark" to if (darkMode) "1" else "0",
            ),
        )
        return classify(url, viewer.props["openInNew"])
    }

    companion object {
        private val CUSTOM_SCHEME = Regex("^(?!https?://)[a-zA-Z0-9]+://")

        /** 替换模板中的 `{$name}` 变量 */
        fun fillTemplate(template: String, vars: Map<String, String>): String =
            vars.entries.fold(template) { acc, (k, v) -> acc.replace("{\$$k}", v) }

        /** 前端规则：非 http(s) 的 `scheme://` 交给系统；`openInNew` 为真值（true/1/yes/on）用外部浏览器；否则内嵌 */
        fun classify(url: String, openInNew: String?): WebViewerTarget = when {
            CUSTOM_SCHEME.containsMatchIn(url) -> WebViewerTarget.ExternalApp(url)
            openInNew in setOf("true", "1", "yes", "on") -> WebViewerTarget.ExternalBrowser(url)
            else -> WebViewerTarget.Embedded(url, viaHandoff = false)
        }
    }
}
