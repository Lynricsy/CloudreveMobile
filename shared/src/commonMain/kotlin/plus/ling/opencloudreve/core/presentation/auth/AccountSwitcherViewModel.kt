package plus.ling.opencloudreve.core.presentation.auth

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.auth.Account
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError

/** 账户列表项 */
data class AccountItem(
    val account: Account,
    /** 头像地址（无需鉴权；用户无头像时服务端返回 404，平台显示首字母占位） */
    val avatarUrl: String,
    val isActive: Boolean,
)

data class AccountSwitcherUiState(
    val accounts: List<AccountItem> = emptyList(),
    val active: AccountItem? = null,
    /** 正在登出的账户 id */
    val signingOutId: String? = null,
    val error: UiError? = null,
)

sealed interface AccountSwitcherIntent {
    data class SwitchTo(val accountId: String) : AccountSwitcherIntent

    /** 登出（撤销 refresh token 并删除本地账户）；若是当前账户则自动切到剩余最早的账户 */
    data class SignOut(val accountId: String) : AccountSwitcherIntent
    data object DismissError : AccountSwitcherIntent
}

/**
 * 账户切换器：列出全部账户、切换、登出。“添加账户”由平台导航到服务器地址页完成。
 * 会话失效的账户（[Account.sessionExpired]）仍在列表中，切换后平台应引导重新登录。
 */
class AccountSwitcherViewModel(
    private val repository: AccountRepository,
) : CoreViewModel<AccountSwitcherUiState, AccountSwitcherIntent, Nothing>(AccountSwitcherUiState()) {

    init {
        scope.launch {
            combine(repository.accounts, repository.activeAccount) { all, active -> all to active?.id }
                .collect { (all, activeId) ->
                    val items = all.map { it.toItem(it.id == activeId) }
                    updateState { s -> s.copy(accounts = items, active = items.firstOrNull { it.isActive }) }
                }
        }
    }

    override fun on(intent: AccountSwitcherIntent) {
        when (intent) {
            is AccountSwitcherIntent.SwitchTo -> try {
                repository.switchTo(intent.accountId)
            } catch (e: Exception) {
                updateState { it.copy(error = e.toUiError()) }
            }
            is AccountSwitcherIntent.SignOut -> signOut(intent.accountId)
            AccountSwitcherIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun signOut(id: String) {
        if (currentState.signingOutId != null) return
        updateState { it.copy(signingOutId = id, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(signingOutId = null, error = e) } }) {
            repository.signOut(id)
            updateState { it.copy(signingOutId = null) }
        }
    }

    private fun Account.toItem(active: Boolean) = AccountItem(
        account = this,
        avatarUrl = avatarUrl(this),
        isActive = active,
    )

    companion object {
        /** `GET /api/v4/user/avatar/:id`；[Account.avatarVersion] 变化时地址变化，使图片缓存失效 */
        fun avatarUrl(account: Account): String =
            "${account.siteUrl}/api/v4/user/avatar/${account.userId}?v=${account.avatarVersion}"
    }
}
