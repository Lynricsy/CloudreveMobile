package plus.ling.opencloudreve.core.presentation.settings

import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.model.Capacity
import plus.ling.opencloudreve.core.model.PatchUserSettingRequest
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.model.UserSettings
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherViewModel
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/** 站点主题色选项（`basic.themes` 的一项；键为主题色本身，也是 `preferred_theme` 的取值） */
data class ThemeColorOption(
    /** 主题键，如 `#1976d2` */
    val key: String,
    /** 浅色模式主色（缺失时等于 [key]） */
    val lightPrimary: String,
    /** 深色模式主色（缺失时等于 [lightPrimary]） */
    val darkPrimary: String,
)

/** 个人主页分享可见性（`share_links_in_profile`） */
object ShareLinksInProfile {
    /** 仅展示公开（无密码）分享；服务端缺省值 */
    const val PUBLIC_ONLY = ""

    /** 展示全部分享 */
    const val ALL = "all_share"

    /** 不展示 */
    const val HIDE = "hide_share"

    val values: List<String> = listOf(PUBLIC_ONLY, ALL, HIDE)
}

/** 正在保存的设置项 */
enum class SettingsField { Nickname, Language, Theme, ViewSync, VersionRetention, ShareLinksInProfile, Avatar }

data class SettingsState(
    val accountId: String,
    val siteUrl: String = "",
    val user: User? = null,
    val settings: UserSettings? = null,
    val capacity: Capacity? = null,
    /** 头像地址（随头像版本变化，使图片缓存失效；无头像时服务端 404，平台显示首字母占位） */
    val avatarUrl: String? = null,
    val themeOptions: List<ThemeColorOption> = emptyList(),
    /** 站点默认主题键（用户未设置偏好时选中） */
    val defaultTheme: String? = null,
    val loading: Boolean = false,
    /** 正在保存的设置项（同一时间只允许一项） */
    val saving: SettingsField? = null,
    /** 加载失败（保存失败通过 [SettingsEffect.Failed] 通知） */
    val error: UiError? = null,
) {
    val nickname: String get() = user?.nickname.orEmpty()
    val email: String get() = user?.email.orEmpty()
    val groupName: String get() = user?.group?.name.orEmpty()

    /** 服务端记录的界面语言（如 `zh-CN`/`en-US`）；null 表示跟随系统 */
    val language: String? get() = user?.language?.takeIf { it.isNotEmpty() }

    /** 当前选中的主题键：用户偏好（且仍在站点选项内）优先，否则站点默认 */
    val selectedTheme: String?
        get() = user?.preferredTheme?.takeIf { p -> p.isNotEmpty() && (themeOptions.isEmpty() || themeOptions.any { it.key == p }) }
            ?: defaultTheme

    val disableViewSync: Boolean get() = settings?.disableViewSync ?: user?.disableViewSync ?: false
    val versionRetentionEnabled: Boolean get() = settings?.versionRetentionEnabled ?: false
    val versionRetentionExt: List<String> get() = settings?.versionRetentionExt.orEmpty()

    /** 最多保留版本数；0 表示不限 */
    val versionRetentionMax: Int get() = settings?.versionRetentionMax ?: 0
    val shareLinksInProfile: String get() = settings?.shareLinksInProfile ?: user?.shareLinksInProfile.orEmpty()

    /** 已用比例（0..∞；总量为 0 时为 0） */
    val capacityUsedFraction: Double
        get() = capacity?.let { if (it.total > 0) it.used.toDouble() / it.total else 0.0 } ?: 0.0

    /** 已用超过总量（前端显示警告色） */
    val overQuota: Boolean get() = capacity?.let { it.used > it.total } ?: false

    /** 移动端支持的界面语言代码 */
    val supportedLanguages: List<String> get() = SUPPORTED_LANGUAGES

    companion object {
        val SUPPORTED_LANGUAGES = listOf("zh-CN", "en-US")
    }
}

sealed interface SettingsIntent {
    data object Refresh : SettingsIntent
    data class SetNickname(val nickname: String) : SettingsIntent

    /** 同步到服务端（邮件等使用）；平台同时在本地覆盖界面语言 */
    data class SetLanguage(val language: String) : SettingsIntent
    data class SetTheme(val themeKey: String) : SettingsIntent
    data class SetDisableViewSync(val disabled: Boolean) : SettingsIntent

    /** [extensions] 会去掉前导 `.`、空白与重复项；[max] 为 0 表示不限 */
    data class SetVersionRetention(val enabled: Boolean, val extensions: List<String>, val max: Int) : SettingsIntent
    data class SetShareLinksInProfile(val level: String) : SettingsIntent

    /** 上传头像原始字节（`PUT /user/setting/avatar`） */
    data class UploadAvatar(val bytes: ByteArray, val contentType: String) : SettingsIntent {
        override fun equals(other: Any?): Boolean =
            other is UploadAvatar && other.contentType == contentType && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }

    /** 改用 Gravatar（空体 `PUT /user/setting/avatar`） */
    data object UseGravatar : SettingsIntent
    data object DismissError : SettingsIntent
}

sealed interface SettingsEffect {
    data class Saved(val field: SettingsField) : SettingsEffect
    data class Failed(val field: SettingsField?, val error: UiError) : SettingsEffect
}

/**
 * “我的”页与设置页：个人资料（头像/昵称）、外观（语言、主题色）、文件管理（视图同步、版本保留）、
 * 个人主页分享可见性、容量。安全相关见 [SecurityViewModel]。
 */
class SettingsViewModel(
    val accountId: String,
    private val userSettings: UserSettingsRepository,
    private val accounts: AccountRepository,
    private val site: SiteConfigRepository,
) : CoreViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState(accountId)) {

    init {
        scope.launch { userSettings.user.collect { u -> if (u != null) updateState { it.copy(user = u) } } }
        scope.launch { userSettings.capacity.collect { c -> if (c != null) updateState { it.copy(capacity = c) } } }
        updateState { it.copy(siteUrl = accounts.account(accountId)?.siteUrl.orEmpty(), avatarUrl = avatarUrl()) }
        load()
    }

    override fun on(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Refresh -> load()
            is SettingsIntent.SetNickname -> {
                val nick = intent.nickname.trim()
                if (nick.isEmpty() || nick.length > 255) {
                    sendEffect(SettingsEffect.Failed(SettingsField.Nickname, UiError(ErrorKind.Validation)))
                } else {
                    patch(SettingsField.Nickname, PatchUserSettingRequest(nick = nick))
                }
            }
            is SettingsIntent.SetLanguage -> patch(SettingsField.Language, PatchUserSettingRequest(language = intent.language))
            is SettingsIntent.SetTheme -> patch(SettingsField.Theme, PatchUserSettingRequest(preferredTheme = intent.themeKey))
            is SettingsIntent.SetDisableViewSync ->
                patch(SettingsField.ViewSync, PatchUserSettingRequest(disableViewSync = intent.disabled))
            is SettingsIntent.SetVersionRetention -> {
                if (intent.max < 0) {
                    sendEffect(SettingsEffect.Failed(SettingsField.VersionRetention, UiError(ErrorKind.Validation)))
                } else {
                    patch(
                        SettingsField.VersionRetention,
                        PatchUserSettingRequest(
                            versionRetentionEnabled = intent.enabled,
                            versionRetentionExt = normalizeExtensions(intent.extensions),
                            versionRetentionMax = intent.max,
                        ),
                    )
                }
            }
            is SettingsIntent.SetShareLinksInProfile ->
                patch(SettingsField.ShareLinksInProfile, PatchUserSettingRequest(shareLinksInProfile = intent.level))
            is SettingsIntent.UploadAvatar -> avatar { userSettings.uploadAvatar(intent.bytes, intent.contentType) }
            SettingsIntent.UseGravatar -> avatar { userSettings.useGravatar() }
            SettingsIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val me = userSettings.refreshMe()
            val settings = userSettings.settings()
            updateState { it.copy(user = me, settings = settings) }
            // 容量与主题失败不影响设置页主体
            runCatching { userSettings.refreshCapacity() }
            runCatching { site.basic(accountId) }.getOrNull()?.let { basic ->
                updateState { it.copy(themeOptions = parseThemes(basic.themes), defaultTheme = basic.defaultTheme) }
            }
            updateState { it.copy(loading = false) }
        }
    }

    private fun patch(field: SettingsField, request: PatchUserSettingRequest) {
        if (currentState.saving != null) return
        updateState { it.copy(saving = field) }
        launchCatching(onError = { e -> fail(field, e) }) {
            userSettings.patchSettings(request) // 随后刷新 user
            val settings = userSettings.settings()
            updateState { it.copy(saving = null, settings = settings) }
            sendEffect(SettingsEffect.Saved(field))
        }
    }

    private fun avatar(block: suspend () -> Unit) {
        if (currentState.saving != null) return
        updateState { it.copy(saving = SettingsField.Avatar) }
        launchCatching(onError = { e -> fail(SettingsField.Avatar, e) }) {
            block()
            accounts.bumpAvatarVersion(accountId)
            updateState { it.copy(saving = null, avatarUrl = avatarUrl()) }
            sendEffect(SettingsEffect.Saved(SettingsField.Avatar))
        }
    }

    private fun fail(field: SettingsField, e: UiError) {
        updateState { it.copy(saving = null) }
        sendEffect(SettingsEffect.Failed(field, e))
    }

    private fun avatarUrl(): String? = accounts.account(accountId)?.let(AccountSwitcherViewModel::avatarUrl)

    companion object {
        /** 版本保留扩展名：去掉前导 `.` 与空白，转小写，去重 */
        fun normalizeExtensions(exts: List<String>): List<String> =
            exts.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.distinct()

        /**
         * 解析站点 `themes`（JSON 字符串：`{"#1976d2":{"light":{"palette":{"primary":{"main":…}}},"dark":{…}}}`）。
         * 格式错误返回空列表。
         */
        fun parseThemes(themes: String?): List<ThemeColorOption> {
            if (themes.isNullOrBlank()) return emptyList()
            val root = try {
                CloudreveJson.decodeFromString(JsonObject.serializer(), themes)
            } catch (e: Exception) {
                return emptyList()
            }
            return root.map { (key, value) ->
                val light = primaryOf(value as? JsonObject, "light") ?: key
                val dark = primaryOf(value as? JsonObject, "dark") ?: light
                ThemeColorOption(key, light, dark)
            }
        }

        private fun primaryOf(theme: JsonObject?, mode: String): String? = try {
            val primary = ((theme?.get(mode) as? JsonObject)?.get("palette") as? JsonObject)?.get("primary") as? JsonObject
            primary?.get("main")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}
