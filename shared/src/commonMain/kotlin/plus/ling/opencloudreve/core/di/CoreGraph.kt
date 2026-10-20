package plus.ling.opencloudreve.core.di

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.LoginFlow
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.PkceOAuth
import plus.ling.opencloudreve.core.auth.SessionStore
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherViewModel
import plus.ling.opencloudreve.core.presentation.auth.LoginParams
import plus.ling.opencloudreve.core.presentation.auth.LoginViewModel
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupViewModel
import plus.ling.opencloudreve.core.transfer.ChunkMaterializer
import plus.ling.opencloudreve.core.transfer.DownloadPlanner
import plus.ling.opencloudreve.core.transfer.TransferExecutor
import plus.ling.opencloudreve.core.transfer.TransferExecutorHolder
import plus.ling.opencloudreve.core.transfer.TransferManager
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.UploadPlanner

/**
 * 共享核心的依赖图入口（Koin 全局容器）。
 *
 * - Android：`CoreGraph.startAndroid(context, appVersion)`（androidMain 扩展），之后 Compose 用 `koinViewModel()`；
 * - iOS：`CoreGraph.shared.startIos(appGroupId:keychainAccessGroup:appVersion:)`（iosMain 扩展），
 *   之后通过本对象的访问器与 VM 工厂方法取实例。
 *
 * 主 App 与各扩展进程各自启动一次；重复调用 [start] 无副作用。
 */
object CoreGraph {

    /** 已启动的 Koin 容器；未启动时访问抛 [IllegalStateException] */
    val koin: Koin
        get() = KoinPlatform.getKoinOrNull() ?: error("CoreGraph not started")

    /** 是否已启动 */
    val isStarted: Boolean get() = KoinPlatform.getKoinOrNull() != null

    /**
     * 以平台上下文启动依赖图。已启动时直接返回（扩展进程/测试重复调用安全）。
     * @param extraModules 平台追加的模块（如 Android 应用自己的 Koin 模块）
     */
    fun start(platform: PlatformContext, extraModules: List<Module> = emptyList()) {
        if (isStarted) return
        startKoin {
            modules(listOf(platformModule(platform), authModule, transferModule, authPresentationModule) + extraModules)
        }
    }

    /** 停止依赖图（仅测试使用） */
    internal fun stop() {
        stopKoin()
    }

    private fun platformModule(platform: PlatformContext) = module {
        single<PlatformContext> { platform }
    }

    // ---- 账户与登录 ----

    fun accounts(): AccountRepository = koin.get()
    fun sessionStore(): SessionStore = koin.get()
    fun apiClients(): ApiClientRegistry = koin.get()
    fun loginFlow(): LoginFlow = koin.get()
    fun pkceOAuth(): PkceOAuth = koin.get()
    fun passkeyFlow(): PasskeyFlow = koin.get()
    fun platform(): PlatformContext = koin.get()

    // ---- 登录与账户 ViewModel ----

    fun serverSetupViewModel(): ServerSetupViewModel = koin.get()

    fun loginViewModel(siteUrl: String, email: String? = null, language: String? = null): LoginViewModel =
        koin.get { parametersOf(LoginParams(siteUrl, email, language)) }

    fun accountSwitcherViewModel(): AccountSwitcherViewModel = koin.get()

    // ---- 传输 ----

    fun transferManager(): TransferManager = koin.get()
    fun transferRepository(): TransferRepository = koin.get()
    fun uploadPlanner(): UploadPlanner = koin.get()
    fun downloadPlanner(): DownloadPlanner = koin.get()
    fun chunkMaterializer(): ChunkMaterializer = koin.get()

    /** 安装平台传输执行器（Android WorkManager / iOS 后台 URLSession），应在启动后立即调用 */
    fun installExecutor(executor: TransferExecutor) {
        koin.get<TransferExecutorHolder>().install(executor)
    }
}
