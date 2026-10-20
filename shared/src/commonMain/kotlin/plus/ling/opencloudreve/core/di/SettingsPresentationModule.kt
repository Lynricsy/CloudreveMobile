package plus.ling.opencloudreve.core.di

import org.koin.core.module.Module
import org.koin.dsl.module
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsViewModel
import plus.ling.opencloudreve.core.presentation.settings.PoliciesViewModel
import plus.ling.opencloudreve.core.presentation.settings.SecurityViewModel
import plus.ling.opencloudreve.core.presentation.settings.SettingsViewModel
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/**
 * 设置相关 ViewModel 与网页会话交接工具（需要 authModule、dataModule）。
 * - [WebHandoff]：single（服务端版本按站点缓存）
 * - [SettingsViewModel]、[SecurityViewModel]、[DavAccountsViewModel]、[PoliciesViewModel]：参数 `(accountId)`
 */
val settingsPresentationModule: Module = module {
    single {
        val accounts = get<AccountRepository>()
        val site = get<SiteConfigRepository>()
        val data = get<DataRepositories>()
        WebHandoff(
            siteUrlOf = { id -> accounts.account(id)?.siteUrl },
            ping = { siteUrl -> site.ping(siteUrl) },
            createCode = { id -> data.userSettings(id).createHandoff().code },
        )
    }
    factory { (accountId: String) ->
        val d = get<DataRepositories>()
        SettingsViewModel(accountId, d.userSettings(accountId), get(), d.site)
    }
    factory { (accountId: String) ->
        val d = get<DataRepositories>()
        SecurityViewModel(accountId, d.userSettings(accountId), get(), get(), d.site, get<PlatformContext>().platformName)
    }
    factory { (accountId: String) ->
        val d = get<DataRepositories>()
        DavAccountsViewModel(accountId, d.dav(accountId), d.userSettings(accountId), get())
    }
    factory { (accountId: String) -> PoliciesViewModel(accountId, get<DataRepositories>().userSettings(accountId)) }
}
