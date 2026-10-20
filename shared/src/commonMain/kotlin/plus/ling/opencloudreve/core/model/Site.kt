package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import plus.ling.opencloudreve.core.api.CloudreveJson

/**
 * `GET /site/config/:section` 的各 section（`service/basic/site.go:GetSiteConfig`）。
 * 服务端所有字段都是 omitempty，缺失按默认值处理。
 */
object SiteSection {
    /** 任意非已知 section 都返回基础配置；与前端一致使用 `basic` */
    const val BASIC = "basic"
    const val LOGIN = "login"
    const val EXPLORER = "explorer"
    const val EMOJIS = "emojis"
    const val APP = "app"
    const val THUMB = "thumb"
}

/** 验证码类型（`captcha_type`，在基础配置中） */
object CaptchaType {
    const val NORMAL = "normal"
    const val RECAPTCHA = "recaptcha"

    /** 已弃用，按 normal 处理 */
    const val TCAPTCHA = "tcaptcha"
    const val TURNSTILE = "turnstile"
    const val CAP = "cap"
}

/** 基础配置（默认分支）；[user] 为当前用户，匿名时 `anonymous=true` */
@Serializable
data class SiteBasic(
    @SerialName("instance_id") val instanceId: String? = null,
    val title: String = "",
    /** 主题配置 JSON 字符串（原样透传） */
    val themes: String? = null,
    @SerialName("default_theme") val defaultTheme: String? = null,
    val user: User? = null,
    val logo: String? = null,
    @SerialName("logo_light") val logoLight: String? = null,
    /** normal / recaptcha / tcaptcha / turnstile / cap；缺失按 normal */
    @SerialName("captcha_type") val captchaType: String = CaptchaType.NORMAL,
    @SerialName("captcha_ReCaptchaKey") val reCaptchaKey: String? = null,
    @SerialName("turnstile_site_id") val turnstileSiteId: String? = null,
    @SerialName("captcha_cap_instance_url") val capInstanceUrl: String? = null,
    @SerialName("captcha_cap_site_key") val capSiteKey: String? = null,
    @SerialName("captcha_cap_asset_server") val capAssetServer: String? = null,
    @SerialName("app_promotion") val appPromotion: Boolean = false,
    @SerialName("custom_nav_items") val customNavItems: List<CustomNavItem> = emptyList(),
)

@Serializable
data class CustomNavItem(
    val icon: String = "",
    val name: String = "",
    val url: String = "",
)

/** `login` section */
@Serializable
data class SiteLogin(
    @SerialName("login_captcha") val loginCaptcha: Boolean = false,
    @SerialName("reg_captcha") val regCaptcha: Boolean = false,
    @SerialName("forget_captcha") val forgetCaptcha: Boolean = false,
    /** 站点是否启用 Passkey */
    val authn: Boolean = false,
    @SerialName("register_enabled") val registerEnabled: Boolean = false,
    @SerialName("tos_url") val tosUrl: String? = null,
    @SerialName("privacy_policy_url") val privacyPolicyUrl: String? = null,
)

/** `explorer` section */
@Serializable
data class SiteExplorer(
    /** 批量 uri 上限（max_batched_file）；缺失时服务端默认 3000 */
    @SerialName("max_batch_size") val maxBatchSize: Int = 0,
    @SerialName("file_viewers") val fileViewers: List<ViewerGroup> = emptyList(),
    /** 扩展名 → viewer id */
    @SerialName("default_viewer_mapping") val defaultViewerMapping: Map<String, String> = emptyMap(),
    /** 文件图标配置的 JSON 字符串，用 [parsedIcons] 解析 */
    val icons: String? = null,
    /** openstreetmap / google / mapbox */
    @SerialName("map_provider") val mapProvider: String? = null,
    @SerialName("google_map_tile_type") val googleMapTileType: String? = null,
    @SerialName("mapbox_ak") val mapboxAk: String? = null,
    @SerialName("thumbnail_width") val thumbnailWidth: Int = 0,
    @SerialName("thumbnail_height") val thumbnailHeight: Int = 0,
    @SerialName("custom_props") val customProps: List<CustomPropDefinition> = emptyList(),
    @SerialName("show_encryption_status") val showEncryptionStatus: Boolean = false,
    @SerialName("full_text_search") val fullTextSearch: Boolean = false,
) {
    /** 所有 viewer（展开分组） */
    val viewers: List<Viewer> get() = fileViewers.flatMap { it.viewers }

    /** 解析 [icons]；格式错误返回空列表 */
    fun parsedIcons(): List<FileTypeIcon> = icons?.takeIf { it.isNotBlank() }?.let {
        runCatching { CloudreveJson.decodeFromString(ListSerializer(FileTypeIcon.serializer()), it) }.getOrNull()
    } ?: emptyList()
}

/** `emojis` section */
@Serializable
data class SiteEmojis(
    /** JSON 字符串：`{"<分类图标>": ["😀", ...]}`，用 [parsedPreset] 解析 */
    @SerialName("emoji_preset") val emojiPreset: String? = null,
) {
    fun parsedPreset(): Map<String, List<String>> = emojiPreset?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            CloudreveJson.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), it)
        }.getOrNull()
    } ?: emptyMap()
}

/** `app` section */
@Serializable
data class SiteApp(
    @SerialName("app_promotion") val appPromotion: Boolean = false,
    @SerialName("desktop_app_promotion") val desktopAppPromotion: Boolean = false,
)

/** `thumb` section：主站已启用的缩略图生成器支持的扩展名（小写、已排序） */
@Serializable
data class SiteThumb(
    @SerialName("thumb_exts") val thumbExts: List<String> = emptyList(),
)

/** `GET /site/captcha`（仅 normal 类型）；[image] 可能带或不带 `data:image/png;base64,` 前缀 */
@Serializable
data class CaptchaImage(
    val image: String = "",
    val ticket: String = "",
)

/** 预览器分组（外层只有 viewers） */
@Serializable
data class ViewerGroup(val viewers: List<Viewer> = emptyList())

/** 预览器（`types.Viewer`；explorer 配置中 wopi_actions 恒为空，未建模） */
@Serializable
data class Viewer(
    val id: String,
    /** builtin / wopi / custom */
    val type: String = "",
    @SerialName("display_name") val displayName: String = "",
    val exts: List<String> = emptyList(),
    val url: String? = null,
    val icon: String? = null,
    val props: Map<String, String> = emptyMap(),
    /** 仅提示，不阻止打开 */
    @SerialName("max_size") val maxSize: Long = 0,
    val disabled: Boolean = false,
    val templates: List<NewFileTemplate> = emptyList(),
    /** pc / mobile / all；缺失视为 all */
    val platform: String? = null,
    /** 需要的用户组权限位（[GroupPermission]） */
    @SerialName("required_group_permission") val requiredGroupPermission: List<Int> = emptyList(),
) {
    companion object {
        const val TYPE_BUILTIN = "builtin"
        const val TYPE_WOPI = "wopi"
        const val TYPE_CUSTOM = "custom"
        const val PLATFORM_PC = "pc"
        const val PLATFORM_MOBILE = "mobile"
        const val PLATFORM_ALL = "all"
    }
}

/** 新建文件模板 */
@Serializable
data class NewFileTemplate(
    val ext: String = "",
    @SerialName("display_name") val displayName: String = "",
)

/** 自定义属性定义（`types.CustomProps`），值通过元数据 `props:<id>` 读写 */
@Serializable
data class CustomPropDefinition(
    val id: String,
    val name: String = "",
    /** text / number / boolean / select / multi_select / link / rating / user */
    val type: String = "",
    val max: Int = 0,
    val min: Int = 0,
    val default: String? = null,
    val options: List<String> = emptyList(),
    val icon: String? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_SELECT = "select"
        const val TYPE_MULTI_SELECT = "multi_select"
        const val TYPE_LINK = "link"
        const val TYPE_RATING = "rating"
        const val TYPE_USER = "user"
    }
}

/** 文件类型图标设置（`types.FileTypeIconSetting`；前端还可能带 `iconify`） */
@Serializable
data class FileTypeIcon(
    val exts: List<String> = emptyList(),
    val icon: String? = null,
    val iconify: String? = null,
    val color: String? = null,
    @SerialName("color_dark") val colorDark: String? = null,
    val img: String? = null,
)
