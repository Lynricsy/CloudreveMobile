package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/**
 * 用户（`service/user/response.go:User`）。他人信息（`BuildUserRedacted`）不含邮箱、状态、用户组细节。
 * 管理端用户见 `AdminUser`。
 */
@Serializable
data class User(
    val id: String,
    val email: String? = null,
    val nickname: String = "",
    /** `active` / `inactive` / `manual_banned` / `sys_banned` */
    val status: String? = null,
    val avatar: String? = null,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("preferred_theme") val preferredTheme: String? = null,
    val anonymous: Boolean = false,
    val group: Group? = null,
    val pined: List<PinedFile> = emptyList(),
    val language: String? = null,
    @SerialName("disable_view_sync") val disableViewSync: Boolean = false,
    /** ""（仅公开分享）/ `all_share` / `hide_share`；缺失即 "" */
    @SerialName("share_links_in_profile") val shareLinksInProfile: String = "",
) {
    /** 是否拥有某个用户组权限位（见 [GroupPermission]） */
    fun hasPermission(bit: Int): Boolean = group?.permission?.has(bit) == true

    val isAdmin: Boolean get() = hasPermission(GroupPermission.IS_ADMIN)

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_MANUAL_BANNED = "manual_banned"
        const val STATUS_SYS_BANNED = "sys_banned"
    }
}

/** 用户组（`response.go:Group`） */
@Serializable
data class Group(
    val id: String,
    val name: String = "",
    val permission: BoolSet? = null,
    /** 单次直链批量上限；0 表示不允许直链 */
    @SerialName("direct_link_batch_size") val directLinkBatchSize: Int = 0,
    /** 回收站保留秒数；0 表示不自动清理 */
    @SerialName("trash_retention") val trashRetention: Int = 0,
)

/** 侧栏固定项（`types.PinedFile`）；`locked` 为用户组默认固定的分享，不可取消 */
@Serializable
data class PinedFile(
    val uri: String,
    val name: String? = null,
    val locked: Boolean = false,
)

/** 用户已注册的 Passkey（`response.go:Passkey`） */
@Serializable
data class Passkey(
    val id: String,
    val name: String = "",
    @SerialName("used_at") val usedAt: CrInstant? = null,
    @SerialName("created_at") val createdAt: CrInstant? = null,
)

/** 已授权的 OAuth 应用（`response.go:OauthGrant`） */
@Serializable
data class OAuthGrant(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String = "",
    @SerialName("client_logo") val clientLogo: String = "",
    val scopes: List<String> = emptyList(),
    @SerialName("last_used_at") val lastUsedAt: CrInstant? = null,
)

/** `GET /user/setting`（`response.go:UserSettings`） */
@Serializable
data class UserSettings(
    @SerialName("version_retention_enabled") val versionRetentionEnabled: Boolean = false,
    @SerialName("version_retention_ext") val versionRetentionExt: List<String> = emptyList(),
    @SerialName("version_retention_max") val versionRetentionMax: Int = 0,
    /** 用户没有密码（仅 Passkey/第三方登录） */
    val passwordless: Boolean = false,
    @SerialName("two_fa_enabled") val twoFaEnabled: Boolean = false,
    val passkeys: List<Passkey> = emptyList(),
    @SerialName("disable_view_sync") val disableViewSync: Boolean = false,
    @SerialName("share_links_in_profile") val shareLinksInProfile: String = "",
    @SerialName("oauth_grants") val oauthGrants: List<OAuthGrant> = emptyList(),
)

/**
 * `PATCH /user/setting`（`service/user/setting.go:PatchUserSetting`），所有字段可选，null 不发送。
 * 修改密码需同时给 [currentPassword] 与 [newPassword]；开关 2FA 需给 [twoFaCode]。
 */
@Serializable
data class PatchUserSettingRequest(
    val nick: String? = null,
    val language: String? = null,
    @SerialName("preferred_theme") val preferredTheme: String? = null,
    @SerialName("version_retention_enabled") val versionRetentionEnabled: Boolean? = null,
    @SerialName("version_retention_ext") val versionRetentionExt: List<String>? = null,
    @SerialName("version_retention_max") val versionRetentionMax: Int? = null,
    @SerialName("current_password") val currentPassword: String? = null,
    @SerialName("new_password") val newPassword: String? = null,
    @SerialName("two_fa_enabled") val twoFaEnabled: Boolean? = null,
    @SerialName("two_fa_code") val twoFaCode: String? = null,
    @SerialName("disable_view_sync") val disableViewSync: Boolean? = null,
    @SerialName("share_links_in_profile") val shareLinksInProfile: String? = null,
)

/** `GET /user/capacity` */
@Serializable
data class Capacity(
    val total: Long = 0,
    val used: Long = 0,
)
