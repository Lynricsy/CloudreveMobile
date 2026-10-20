package plus.ling.opencloudreve.core.auth

import plus.ling.opencloudreve.core.db.AccountRecord
import kotlin.time.Instant

/**
 * 本机保存的一个账户（某服务器上的某个用户）。令牌不在此对象中，见 [SessionStore]。
 *
 * - [id]：`"<userId>@<站点地址去掉协议与尾部 />"`，见 [SiteUrl.accountId]
 * - [isActive]：当前选中的账户
 * - [sessionExpired]：刷新失败、需要重新登录（账户仍保留以便快速重登）
 * - [avatarVersion]：头像修改后递增，用于拼头像 URL 绕过缓存
 */
data class Account(
    val id: String,
    val siteUrl: String,
    val userId: String,
    val email: String,
    val nickname: String,
    val avatarVersion: Long,
    val isActive: Boolean,
    val sessionExpired: Boolean,
    val createdAt: Instant,
) {
    /** 站点主机部分（含端口与子路径），用于显示 “昵称 @ host” */
    val host: String get() = SiteUrl.hostOf(siteUrl)
}

internal fun AccountRecord.toAccount(): Account = Account(
    id = id,
    siteUrl = server_url,
    userId = user_id,
    email = email,
    nickname = nickname,
    avatarVersion = avatar_version,
    isActive = is_active != 0L,
    sessionExpired = session_expired != 0L,
    createdAt = Instant.fromEpochMilliseconds(created_at),
)

/** 账户会话事件 */
sealed class AuthEvent {
    abstract val accountId: String

    /** 登录（含重新登录）成功 */
    data class SignedIn(override val accountId: String) : AuthEvent()

    /** 已登出：[expired] 为 true 表示刷新失败被动登出，false 表示用户主动登出（账户已删除） */
    data class SignedOut(override val accountId: String, val expired: Boolean) : AuthEvent()
}

/** 站点地址工具 */
object SiteUrl {
    /** 规范化用户输入：去首尾空白，缺协议时补 `https://`，去掉尾部 `/` */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.trimEnd('/')
    }

    /** 去掉协议后的主机部分（保留端口与子路径），小写 */
    fun hostOf(siteUrl: String): String = normalize(siteUrl).substringAfter("://").lowercase()

    /** 账户 id：同一服务器同一用户稳定不变 */
    fun accountId(siteUrl: String, userId: String): String = "$userId@${hostOf(siteUrl)}"
}
