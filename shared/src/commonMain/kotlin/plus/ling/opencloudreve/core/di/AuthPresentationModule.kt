package plus.ling.opencloudreve.core.di

import org.koin.core.module.Module
import org.koin.dsl.module
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherViewModel
import plus.ling.opencloudreve.core.presentation.auth.LoginParams
import plus.ling.opencloudreve.core.presentation.auth.LoginViewModel
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupViewModel

/**
 * 登录与账户相关 ViewModel（每次获取新实例）。
 * - [ServerSetupViewModel]：无参数
 * - [LoginViewModel]：参数 [LoginParams]（`parametersOf(LoginParams(siteUrl, email, language))`）
 * - [AccountSwitcherViewModel]：无参数
 */
val authPresentationModule: Module = module {
    factory { ServerSetupViewModel(get()) }
    factory { (params: LoginParams) -> LoginViewModel(params, get(), get(), get(), get()) }
    factory { AccountSwitcherViewModel(get()) }
}
