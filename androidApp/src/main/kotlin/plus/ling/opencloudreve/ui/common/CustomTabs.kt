package plus.ling.opencloudreve.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import plus.ling.opencloudreve.R

/**
 * 用 Chrome Custom Tabs 打开网页（会话交接地址、网页后台等）；设备没有支持 Custom Tabs 的浏览器时
 * 退回普通 `ACTION_VIEW`，仍无应用可处理则提示。
 */
fun openCustomTab(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
