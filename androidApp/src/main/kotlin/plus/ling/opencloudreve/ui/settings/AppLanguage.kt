package plus.ling.opencloudreve.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi

/**
 * 应用内语言（Android 13+ `LocaleManager`，与系统设置“应用语言”同源）。
 * 服务端语言代码 `zh-CN`/`en-US` 与应用支持的区域 `zh-CN`/`en`（见 `locales_config.xml`）互相换算。
 */
object AppLanguage {
    /** 系统是否支持按应用切换语言 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 当前应用语言对应的服务端语言代码；null 表示跟随系统（或系统不支持按应用设置） */
    fun current(context: Context): String? {
        if (!supported) return null
        return currentApi33(context)
    }

    /** 设为 [serverLanguage]（null 表示跟随系统）；系统会重建 Activity 使之生效 */
    fun apply(context: Context, serverLanguage: String?) {
        if (!supported) return
        applyApi33(context, serverLanguage)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun currentApi33(context: Context): String? {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return null
        return if (locales[0].language == "zh") "zh-CN" else "en-US"
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyApi33(context: Context, serverLanguage: String?) {
        val tag = when (serverLanguage) {
            null -> null
            "zh-CN", "zh-TW" -> "zh-CN"
            else -> "en"
        }
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
