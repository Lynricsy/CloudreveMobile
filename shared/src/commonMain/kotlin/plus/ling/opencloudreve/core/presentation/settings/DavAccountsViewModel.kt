package plus.ling.opencloudreve.core.presentation.settings

import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.device.DavRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.DavAccount
import plus.ling.opencloudreve.core.model.DavAccountRequest
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository

/** 连接 WebDAV 所需的三项（“复制地址”时平台按需组合） */
data class DavCredentials(
    /** `<站点>/dav` */
    val url: String,
    /** HTTP Basic 用户名 = 账户邮箱 */
    val username: String,
    val password: String,
)

data class DavAccountItem(
    val account: DavAccount,
    val credentials: DavCredentials,
) {
    val id: String get() = account.id
    val readonly: Boolean get() = account.readonly
    val proxy: Boolean get() = account.proxy
    val disableSysFiles: Boolean get() = account.disableSysFiles
}

/**
 * 新建/编辑表单。[uri] 为根目录（`cloudreve://my/...` 或分享 URI，用 FolderPicker 选择）；
 * [proxy] 仅在用户组有 webdav_proxy 权限时提交。
 */
data class DavAccountDraft(
    val name: String,
    val uri: String,
    val readonly: Boolean = false,
    val proxy: Boolean = false,
    val disableSysFiles: Boolean = false,
) {
    companion object {
        /** 以现有账号为初值（编辑） */
        fun from(account: DavAccount): DavAccountDraft =
            DavAccountDraft(account.name, account.uri, account.readonly, account.proxy, account.disableSysFiles)
    }
}

data class DavAccountsState(
    val accountId: String,
    val items: List<DavAccountItem> = emptyList(),
    val loading: Boolean = false,
    /** 加载失败（操作失败通过 [DavAccountsEffect.Failed] 通知） */
    val error: UiError? = null,
    /** `<站点>/dav` */
    val davUrl: String = "",
    /** 用户名（账户邮箱） */
    val username: String = "",
    /** 用户组允许 WebDAV（否则只显示说明，不显示新建按钮） */
    val webdavEnabled: Boolean = false,
    /** 用户组允许“反向代理”选项 */
    val canProxy: Boolean = false,
    val saving: Boolean = false,
    val deletingId: String? = null,
) {
    val canCreate: Boolean get() = webdavEnabled && !saving
}

sealed interface DavAccountsIntent {
    data object Refresh : DavAccountsIntent
    data class Create(val draft: DavAccountDraft) : DavAccountsIntent
    data class Update(val id: String, val draft: DavAccountDraft) : DavAccountsIntent
    data class Delete(val id: String) : DavAccountsIntent
    data object DismissError : DavAccountsIntent
}

sealed interface DavAccountsEffect {
    data class Saved(val item: DavAccountItem, val created: Boolean) : DavAccountsEffect
    data class Deleted(val id: String) : DavAccountsEffect
    data class Failed(val error: UiError) : DavAccountsEffect
}

/** WebDAV 账号管理（`/devices/dav`） */
class DavAccountsViewModel(
    val accountId: String,
    private val dav: DavRepository,
    private val userSettings: UserSettingsRepository,
    private val accounts: AccountRepository,
) : CoreViewModel<DavAccountsState, DavAccountsIntent, DavAccountsEffect>(DavAccountsState(accountId)) {

    init {
        load()
    }

    override fun on(intent: DavAccountsIntent) {
        when (intent) {
            DavAccountsIntent.Refresh -> load()
            is DavAccountsIntent.Create -> save(null, intent.draft)
            is DavAccountsIntent.Update -> save(intent.id, intent.draft)
            is DavAccountsIntent.Delete -> delete(intent.id)
            DavAccountsIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val me = userSettings.me()
            val url = dav.davUrl()
            val username = me.email ?: accounts.account(accountId)?.email.orEmpty()
            updateState {
                it.copy(
                    davUrl = url,
                    username = username,
                    webdavEnabled = me.hasPermission(GroupPermission.WEBDAV),
                    canProxy = me.hasPermission(GroupPermission.WEBDAV_PROXY),
                )
            }
            val list = dav.listAll()
            updateState { s -> s.copy(loading = false, items = list.map { s.item(it) }) }
        }
    }

    private fun save(id: String?, draft: DavAccountDraft) {
        if (currentState.saving) return
        val request = validate(draft) ?: return sendEffect(DavAccountsEffect.Failed(UiError(ErrorKind.Validation)))
        updateState { it.copy(saving = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(saving = false) }
            sendEffect(DavAccountsEffect.Failed(e))
        }) {
            val saved = if (id == null) dav.create(request) else dav.update(id, request)
            val item = currentState.item(saved)
            updateState { s ->
                val items = if (id == null) listOf(item) + s.items else s.items.map { if (it.id == id) item else it }
                s.copy(saving = false, items = items)
            }
            sendEffect(DavAccountsEffect.Saved(item, created = id == null))
        }
    }

    private fun delete(id: String) {
        if (currentState.deletingId != null) return
        updateState { it.copy(deletingId = id) }
        launchCatching(onError = { e ->
            updateState { it.copy(deletingId = null) }
            sendEffect(DavAccountsEffect.Failed(e))
        }) {
            dav.delete(id)
            updateState { s -> s.copy(deletingId = null, items = s.items.filterNot { it.id == id }) }
            sendEffect(DavAccountsEffect.Deleted(id))
        }
    }

    /** 名称非空；根目录只能是 my/share 文件系统且不能带搜索参数 */
    private fun validate(draft: DavAccountDraft): DavAccountRequest? {
        val name = draft.name.trim()
        val uri = CrUri.parseOrNull(draft.uri.trim()) ?: return null
        if (name.isEmpty() || uri.isSearch() || uri.fs !in setOf(CrUri.FS_MY, CrUri.FS_SHARE)) return null
        return DavAccountRequest(
            uri = uri.toString(),
            name = name,
            readonly = draft.readonly,
            proxy = draft.proxy && currentState.canProxy,
            disableSysFiles = draft.disableSysFiles,
        )
    }

    private fun DavAccountsState.item(account: DavAccount) =
        DavAccountItem(account, DavCredentials(davUrl, username, account.password))
}
