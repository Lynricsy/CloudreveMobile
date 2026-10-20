package plus.ling.opencloudreve.core.presentation.viewer

import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError

data class ViewerPdfState(
    val file: FileItem,
    /** PDF 签名地址（iOS PDFKit 直接加载；Android 下载到缓存后用 PdfRenderer） */
    val url: ResolvedUrl? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
)

sealed interface ViewerPdfIntent {
    data object Retry : ViewerPdfIntent
}

/** PDF 预览：签名地址（过期前 60 秒刷新） */
class ViewerPdfViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerPdfState, ViewerPdfIntent, Nothing>(ViewerPdfState(params.file)) {
    private val refresh = UrlRefreshScheduler(scope, env.clock)

    init {
        load()
    }

    override fun on(intent: ViewerPdfIntent) {
        when (intent) {
            ViewerPdfIntent.Retry -> load()
        }
    }

    private fun load() {
        updateState { it.copy(loading = it.url == null, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val url = env.url(params.file, params.version, params.contextHint)
            updateState { it.copy(url = url, loading = false) }
            refresh.schedule(url.expiresAt) { load() }
        }
    }
}
