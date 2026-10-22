package plus.ling.opencloudreve.ui.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内存版 SharedPreferences（只实现字符串读写，够 AppLanguage 用） */
private class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = values
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int): Int = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float): Float = throw UnsupportedOperationException()
    override fun getBoolean(key: String, defValue: Boolean): Boolean = throw UnsupportedOperationException()
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long) = throw UnsupportedOperationException()
        override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
        override fun putBoolean(key: String, value: Boolean) = throw UnsupportedOperationException()
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = throw UnsupportedOperationException()
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            removed.forEach(values::remove)
            values.putAll(pending)
        }
    }
}

/** Android 9–12L 的本地语言覆盖：选择 → 持久化的区域标签 → 重启后读回的当前语言 */
class AppLanguageTest {
    private fun choose(prefs: SharedPreferences, serverLanguage: String?) =
        AppLanguage.storeTag(prefs, AppLanguage.localeTagFor(serverLanguage))

    private fun currentAfterRestart(prefs: SharedPreferences) = AppLanguage.serverLanguageFor(AppLanguage.storedTag(prefs))

    @Test
    fun choosingEnglishPersistsEnglishLocale() {
        val prefs = MemoryPrefs()
        choose(prefs, "en-US")
        assertEquals("en", AppLanguage.storedTag(prefs))
        assertEquals("en-US", currentAfterRestart(prefs))
    }

    @Test
    fun choosingTraditionalChineseUsesTheBundledSimplifiedChinese() {
        val prefs = MemoryPrefs()
        choose(prefs, "zh-TW")
        assertEquals("zh-CN", AppLanguage.storedTag(prefs))
        assertEquals("zh-CN", currentAfterRestart(prefs))
    }

    @Test
    fun followingSystemClearsThePreviousOverride() {
        val prefs = MemoryPrefs()
        choose(prefs, "en-US")
        choose(prefs, null)
        assertNull(AppLanguage.storedTag(prefs))
        assertNull(currentAfterRestart(prefs))
    }
}
