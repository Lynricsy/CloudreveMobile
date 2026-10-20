package plus.ling.opencloudreve.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.endpoints.SessionApi
import plus.ling.opencloudreve.core.api.endpoints.UserApi
import plus.ling.opencloudreve.core.model.Capacity
import plus.ling.opencloudreve.core.model.HandoffCode
import plus.ling.opencloudreve.core.model.PatchUserSettingRequest
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.model.UserSettings

/**
 * 单个账户的用户信息与设置仓库。
 * [user]/[capacity] 为最近一次拉取的结果（列表页、设置页共享）；拉到新用户信息时通过 [onProfile] 同步账户表。
 */
class UserSettingsRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
    private val onProfile: (accountId: String, user: User) -> Unit,
) {
    private fun userApi() = UserApi(clients.client(accountId))

    private val mutableUser = MutableStateFlow<User?>(null)
    private val mutableCapacity = MutableStateFlow<Capacity?>(null)

    /** 当前用户（含用户组权限、固定项）；未拉取时为 null */
    val user: StateFlow<User?> = mutableUser.asStateFlow()

    /** 容量；未拉取时为 null */
    val capacity: StateFlow<Capacity?> = mutableCapacity.asStateFlow()

    /** `GET /user/me` 并更新 [user] 与账户资料 */
    @Throws(Exception::class)
    suspend fun refreshMe(): User {
        val me = userApi().me()
        mutableUser.value = me
        onProfile(accountId, me)
        return me
    }

    /** 已有缓存时直接返回，否则拉取 */
    @Throws(Exception::class)
    suspend fun me(): User = mutableUser.value ?: refreshMe()

    @Throws(Exception::class)
    suspend fun refreshCapacity(): Capacity {
        val c = userApi().capacity()
        mutableCapacity.value = c
        return c
    }

    @Throws(Exception::class)
    suspend fun settings(): UserSettings = userApi().settings()

    /** `PATCH /user/setting`；涉及昵称/语言/视图同步等用户字段时随后刷新 [user] */
    @Throws(Exception::class)
    suspend fun patchSettings(request: PatchUserSettingRequest) {
        userApi().patchSettings(request)
        refreshMe()
    }

    @Throws(Exception::class)
    suspend fun policies(): List<StoragePolicy> = userApi().policies()

    @Throws(Exception::class)
    suspend fun uploadAvatar(bytes: ByteArray, contentType: String) {
        userApi().uploadAvatar(bytes, contentType)
    }

    @Throws(Exception::class)
    suspend fun useGravatar() = userApi().useGravatar()

    @Throws(Exception::class)
    fun avatarUrl(userId: String, noCache: Boolean = false): String = userApi().avatarUrl(userId, noCache)

    /** 开启 2FA 前取 TOTP secret；启用/关闭通过 [patchSettings]（`two_fa_enabled` + `two_fa_code`） */
    @Throws(Exception::class)
    suspend fun init2fa(): String = userApi().init2fa()

    @Throws(Exception::class)
    suspend fun deletePasskey(id: String) = userApi().deletePasskey(id)

    @Throws(Exception::class)
    suspend fun revokeOAuthGrant(clientId: String) = SessionApi(clients.client(accountId)).revokeOAuthGrant(clientId)

    /** 网页会话交接码（服务端 ≥ 4.19.11），用于“在网页中打开” */
    @Throws(Exception::class)
    suspend fun createHandoff(): HandoffCode = SessionApi(clients.client(accountId)).handoffCreate()

    @Throws(Exception::class)
    suspend fun searchUsers(keyword: String): List<User> = userApi().search(keyword)

    @Throws(Exception::class)
    suspend fun userInfo(userId: String): User = userApi().info(userId)
}
