package plus.ling.opencloudreve.core.di

import app.cash.sqldelight.db.SqlDriver
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.defaultHttpEngine
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.auth.AnonymousClientPool
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.ClientIdentity
import plus.ling.opencloudreve.core.auth.LoginFlow
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.PkceOAuth
import plus.ling.opencloudreve.core.auth.SessionStore
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.platform.SecretStore

/**
 * 存储、会话、账户与登录。需要容器中已注册 [PlatformContext]（由 CoreGraph 提供）。
 * 提供：[SqlDriver]、[OpenCloudreveDatabase]、[SecretStore]、[ClientIdentity]、[SessionStore]、
 * [ApiClientRegistry]（同时绑定为 [ApiClientProvider]）、[AccountRepository]、[LoginFlow]、[PkceOAuth]、[PasskeyFlow]。
 */
val authModule: Module = module {
    single<SqlDriver> { get<PlatformContext>().databaseDriverFactory.create() }
    single { OpenCloudreveDatabase(get()) }
    single<SecretStore> { get<PlatformContext>().secureStore }
    single { ClientIdentity(get()) }
    single { AnonymousClientPool(get(), ::defaultHttpEngine) }
    single { SessionStore(get(), get(), get()) }
    single { ApiClientRegistry(get(), get(), get(), get(), ::defaultHttpEngine) } bind ApiClientProvider::class
    single { AccountRepository(get(), get(), get()) }
    single { LoginFlow(get(), get()) }
    single { PkceOAuth(get(), get()) }
    single { PasskeyFlow(get(), get()) }
}
