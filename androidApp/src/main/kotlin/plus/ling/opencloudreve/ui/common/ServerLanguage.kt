package plus.ling.opencloudreve.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** 服务端/网页端支持的语言标识（`zh-CN`、`zh-TW`、`en-US`），跟随当前界面语言 */
@Composable
@ReadOnlyComposable
fun serverLanguage(): String = serverLanguage(LocalConfiguration.current.locales[0])

private fun serverLanguage(locale: Locale): String = when (locale.language) {
    "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") "zh-TW" else "zh-CN"
    else -> "en-US"
}
