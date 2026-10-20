package plus.ling.opencloudreve.ui.auth

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import androidx.compose.foundation.layout.Spacer
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.auth.CaptchaKind
import plus.ling.opencloudreve.core.presentation.auth.CaptchaState
import plus.ling.opencloudreve.ui.common.serverLanguage

/**
 * 当前阶段的验证码区域：
 * - [CaptchaKind.Image]：原生图形验证码 + 输入框，点图刷新；
 * - [CaptchaKind.Web]：WebView 加载站点 `/captcha` 页，通过 `CaptchaBridge.onToken(json)` 回传；
 * - [CaptchaKind.Unsupported]：提示在网页端登录。
 */
@Composable
fun CaptchaSection(
    captcha: CaptchaState,
    webUrl: (dark: Boolean, language: String) -> String,
    enabled: Boolean,
    onAnswerChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onWebToken: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    when (captcha.kind) {
        CaptchaKind.None -> Unit
        CaptchaKind.Image -> ImageCaptcha(captcha, enabled, onAnswerChange, onRefresh, onSubmit)
        CaptchaKind.Web -> {
            val dark = isSystemInDarkTheme()
            val url = webUrl(dark, serverLanguage())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // generation 变化（失败后需要重新验证）时重建 WebView
                key(captcha.generation, url) {
                    WebCaptcha(url = url, onToken = onWebToken)
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (captcha.webToken != null) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.captcha_done),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRefresh, enabled = enabled) {
                        Text(stringResource(R.string.captcha_refresh), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        CaptchaKind.Unsupported -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.captcha_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenWeb) {
                Text(stringResource(R.string.action_open_in_browser))
            }
        }
    }
}

@Composable
private fun ImageCaptcha(
    captcha: CaptchaState,
    enabled: Boolean,
    onAnswerChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit,
) {
    val bitmap = remember(captcha.image) {
        captcha.image?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = captcha.answer,
            onValueChange = onAnswerChange,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.captcha_label)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !captcha.loading, onClickLabel = stringResource(R.string.captcha_refresh)) { onRefresh() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                captcha.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.captcha_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(56.dp).background(androidx.compose.ui.graphics.Color.White),
                )
                else -> Text(
                    text = stringResource(R.string.captcha_load_failed),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 站点 `/captcha` 页注入的桥接对象；回调来自 JS 线程，切回主线程 */
private class CaptchaBridge(private val deliver: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onToken(json: String) {
        main.post { deliver(json) }
    }
}

/**
 * 渲染进程崩溃/被回收时不让应用跟着崩溃；用户点“刷新验证码”会重建 WebView。
 * （androidx.webkit 的 MissingOnRenderProcessGone 检查不识别 Kotlin 覆写，已实现仍误报）
 */
@SuppressLint("MissingOnRenderProcessGone")
private class CaptchaWebViewClient : WebViewClient() {
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebCaptcha(url: String, onToken: (String) -> Unit) {
    val callback by rememberUpdatedState(onToken)
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(CaptchaBridge { callback(it) }, "CaptchaBridge")
                webViewClient = CaptchaWebViewClient()
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}
