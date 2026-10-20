package plus.ling.opencloudreve.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 配色来源 */
enum class ThemeSource {
    /** 跟随系统动态取色（Android 12 以下为品牌色） */
    Dynamic,

    /** 站点主题色（`preferred_theme` / 站点默认主题） */
    Site,
}

/**
 * 本机配色偏好。[lightSeed]/[darkSeed] 是所选站点主题的浅色/深色主色（ARGB），
 * 仅在 [source] 为 [ThemeSource.Site] 时使用；缺失时退回动态取色。
 */
data class ThemeSettings(
    val source: ThemeSource = ThemeSource.Dynamic,
    val lightSeed: Int? = null,
    val darkSeed: Int? = null,
)

/** 配色偏好存储（SharedPreferences；变化通过 [settings] 推送，主题立即生效） */
class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("theme", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())

    val settings: StateFlow<ThemeSettings> = mutable.asStateFlow()

    fun useDynamic() = write(ThemeSettings(ThemeSource.Dynamic))

    /** 使用站点主题色；颜色字符串无法解析时保持原值不变 */
    fun useSite(lightPrimary: String, darkPrimary: String) {
        val light = parseColor(lightPrimary) ?: return
        val dark = parseColor(darkPrimary) ?: light
        write(ThemeSettings(ThemeSource.Site, light, dark))
    }

    private fun write(value: ThemeSettings) {
        if (value == mutable.value) return
        prefs.edit {
            putString(KEY_SOURCE, value.source.name)
            if (value.lightSeed != null) putInt(KEY_LIGHT, value.lightSeed) else remove(KEY_LIGHT)
            if (value.darkSeed != null) putInt(KEY_DARK, value.darkSeed) else remove(KEY_DARK)
        }
        mutable.value = value
    }

    private fun read(): ThemeSettings = ThemeSettings(
        source = prefs.getString(KEY_SOURCE, null)?.let { s -> ThemeSource.entries.firstOrNull { it.name == s } }
            ?: ThemeSource.Dynamic,
        lightSeed = if (prefs.contains(KEY_LIGHT)) prefs.getInt(KEY_LIGHT, 0) else null,
        darkSeed = if (prefs.contains(KEY_DARK)) prefs.getInt(KEY_DARK, 0) else null,
    )

    companion object {
        private const val KEY_SOURCE = "source"
        private const val KEY_LIGHT = "light_seed"
        private const val KEY_DARK = "dark_seed"

        /** `#RRGGBB` / `#AARRGGBB` → ARGB；格式错误返回 null */
        fun parseColor(hex: String): Int? = try {
            android.graphics.Color.parseColor(hex.trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
