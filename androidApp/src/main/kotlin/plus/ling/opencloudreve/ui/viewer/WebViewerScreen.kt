package plus.ling.opencloudreve.ui.viewer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.webkit.CookieManager
import plus.ling.opencloudreve.BuildConfig
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerWebIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerWebViewModel
import plus.ling.opencloudreve.core.presentation.viewer.WebViewerTarget
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.common.openCustomTab

/**
 * 网页预览器（epub、drawio、excalidraw、photopea、WOPI、custom）：
 * 内置/WOPI 通过一次性会话交接免登录打开网页文件管理器；custom `openInNew` 用 Custom Tabs；非 http(s) scheme 交给系统。
 */
@Composable
internal fun WebViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val vm: ViewerWebViewModel = koinViewModel(key = "web:${params.file.path}:${params.version}:${params.viewerId}") { parametersOf(params, dark) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val close by rememberUpdatedState(onClose)
    // 交接码只能用一次：页面重建（如旋转）后已加载过的地址必须重新生成
    var loadedUrl by rememberSaveable { mutableStateOf<String?>(null) }

    val target = state.target
    LaunchedEffect(target) {
        when (target) {
            is WebViewerTarget.ExternalBrowser -> {
                openCustomTab(context, target.url)
                close()
            }
            is WebViewerTarget.ExternalApp -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, target.url.toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.viewer_no_app, Toast.LENGTH_SHORT).show()
                }
                close()
            }
            is WebViewerTarget.Embedded -> if (target.viaHandoff && loadedUrl == target.url) vm.on(ViewerWebIntent.Reload)
            null -> Unit
        }
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    ViewerScaffold(
        title = state.viewer?.let { "${params.file.name} · ${viewerLabel(it)}" } ?: params.file.name,
        menu = menu,
        onClose = onClose,
        actions = {
            IconButton(onClick = { vm.on(ViewerWebIntent.Reload) }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.viewer_reload))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val err = state.error
            when {
                state.handoff == HandoffAvailability.Unsupported ->
                    CenteredError(stringResource(R.string.viewer_handoff_unsupported, err?.serverMsg ?: "?"), onRetry = null)
                err != null -> CenteredError(err.message(), onRetry = { vm.on(ViewerWebIntent.Reload) })
                state.loading || target == null -> CenteredProgress()
                target is WebViewerTarget.Embedded && !(target.viaHandoff && loadedUrl == target.url && webView == null) -> {
                    EmbeddedWebView(
                        url = target.url,
                        onCreated = { webView = it },
                        onProgress = { progress = it },
                        onHistoryChanged = { canGoBack = it },
                        onLoaded = { loadedUrl = it },
                    )
                    if (progress in 1..99) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
                else -> CenteredProgress()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedWebView(
    url: String,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onHistoryChanged: (Boolean) -> Unit,
    onLoaded: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            // 内嵌编辑器（draw.io、WOPI 等）在第三方 iframe 中运行，依赖第三方 Cookie
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") return false
                    // 非网页链接（mailto、intent、第三方 App scheme）交给系统
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.viewer_no_app, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = onHistoryChanged(view.canGoBack())

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onHistoryChanged(view.canGoBack())
            }
        }
    }
    LaunchedEffect(url) {
        view.loadUrl(url)
        onLoaded(url)
    }
    DisposableEffect(view) {
        onCreated(view)
        onDispose { view.destroy() }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
