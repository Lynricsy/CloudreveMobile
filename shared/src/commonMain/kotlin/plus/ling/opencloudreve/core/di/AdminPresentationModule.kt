package plus.ling.opencloudreve.core.di

import org.koin.core.module.Module
import org.koin.dsl.module
import plus.ling.opencloudreve.core.presentation.admin.AdminFilesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminNodesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminQueueViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminSharesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminUsersViewModel

/**
 * 轻量管理 ViewModel（需要 dataModule、settingsPresentationModule 的 `WebHandoff`）。
 * 参数 `(accountId)`；[AdminUserEditViewModel] 为 `(accountId, userId: Int)`。
 */
val adminPresentationModule: Module = module {
    factory { (accountId: String) -> AdminSummaryViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
    factory { (accountId: String) -> AdminUsersViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
    factory { (accountId: String, userId: Int) ->
        AdminUserEditViewModel(accountId, userId, get<DataRepositories>().admin(accountId), get())
    }
    factory { (accountId: String) -> AdminSharesViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
    factory { (accountId: String) -> AdminFilesViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
    factory { (accountId: String) -> AdminQueueViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
    factory { (accountId: String) -> AdminNodesViewModel(accountId, get<DataRepositories>().admin(accountId), get()) }
}
