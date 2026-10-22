package plus.ling.opencloudreve.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.util.Locale

/**
 * 应用内语言。服务端语言代码 `zh-CN`/`en-US` 与应用支持的区域 `zh-CN`/`en`（见 `locales_config.xml`）互相换算。
 * - Android 13+：`LocaleManager`（与系统设置“应用语言”同源，系统负责重建 Activity）；
 * - Android 9–12L：自行保存所选区域，Activity 创建时用 [wrap] 换成该区域的资源上下文，切换后重建当前 Activity。
 */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "locale_tag"

    /** 当前应用语言对应的服务端语言代码；null 表示跟随系统 */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) currentApi33(context) else serverLanguageFor(storedTag(prefs(context)))

    /** 设为 [serverLanguage]（null 表示跟随系统）并重建界面使之生效 */
    fun apply(context: Context, serverLanguage: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyApi33(context, serverLanguage)
            return
        }
        storeTag(prefs(context), localeTagFor(serverLanguage))
        context.findActivity()?.recreate()
    }

    /** Android 13 以下：在 Activity 的 `attachBaseContext` 里套上所选区域；跟随系统或 13+ 时原样返回 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(prefs(base))
        if (tag == null) {
            // 从指定语言切回跟随系统：恢复进程默认区域（格式化日期/数字用）
            Locale.setDefault(Resources.getSystem().configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** 服务端语言代码 → 应用区域标签；null（跟随系统）→ null */
    internal fun localeTagFor(serverLanguage: String?): String? = when (serverLanguage) {
        null -> null
        "zh-CN", "zh-TW" -> "zh-CN"
        else -> "en"
    }

    /** 应用区域标签 → 服务端语言代码；null（跟随系统）→ null */
    internal fun serverLanguageFor(tag: String?): String? =
        tag?.let { if (Locale.forLanguageTag(it).language == "zh") "zh-CN" else "en-US" }

    internal fun storeTag(prefs: SharedPreferences, tag: String?) {
        prefs.edit { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }
    }

    internal fun storedTag(prefs: SharedPreferences): String? = prefs.getString(KEY_TAG, null)

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun currentApi33(context: Context): String? {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return null
        return serverLanguageFor(locales[0].toLanguageTag())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyApi33(context: Context, serverLanguage: String?) {
        val tag = localeTagFor(serverLanguage)
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
