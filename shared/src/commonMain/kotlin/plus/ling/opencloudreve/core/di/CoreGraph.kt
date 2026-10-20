package plus.ling.opencloudreve.core.di

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.LoginFlow
import plus.ling.opencloudreve.core.auth.PasskeyFlow
import plus.ling.opencloudreve.core.auth.PkceOAuth
import plus.ling.opencloudreve.core.auth.SessionStore
import plus.ling.opencloudreve.core.device.DavRepository
import plus.ling.opencloudreve.core.events.EventHub
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.platform.PlatformContext
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherViewModel
import plus.ling.opencloudreve.core.presentation.auth.LoginParams
import plus.ling.opencloudreve.core.presentation.auth.LoginViewModel
import plus.ling.opencloudreve.core.presentation.auth.ServerSetupViewModel
import plus.ling.opencloudreve.core.presentation.files.FileActionViewModel
import plus.ling.opencloudreve.core.presentation.files.FileInfoViewModel
import plus.ling.opencloudreve.core.presentation.files.FileListViewModel
import plus.ling.opencloudreve.core.presentation.files.FolderPickerViewModel
import plus.ling.opencloudreve.core.presentation.files.SearchViewModel
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.share.ShareRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import plus.ling.opencloudreve.core.transfer.ChunkMaterializer
import plus.ling.opencloudreve.core.transfer.DownloadPlanner
import plus.ling.opencloudreve.core.transfer.TransferExecutor
import plus.ling.opencloudreve.core.transfer.TransferExecutorHolder
import plus.ling.opencloudreve.core.transfer.TransferManager
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.UploadPlanner
import plus.ling.opencloudreve.core.workflow.WorkflowRepository
import plus.ling.opencloudreve.core.presentation.admin.AdminFilesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminNodesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminQueueViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminSharesViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditViewModel
import plus.ling.opencloudreve.core.presentation.admin.AdminUsersViewModel
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsViewModel
import plus.ling.opencloudreve.core.presentation.settings.PoliciesViewModel
import plus.ling.opencloudreve.core.presentation.settings.SecurityViewModel
import plus.ling.opencloudreve.core.presentation.settings.SettingsViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerArchiveViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerAudioViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerImageViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerPdfViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerTextViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerVideoViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerWebViewModel

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
            modules(
                listOf(platformModule(platform), authModule, transferModule, dataModule, authPresentationModule) +
                    listOf(settingsPresentationModule, adminPresentationModule, viewerPresentationModule) +
                    extraModules,
            )
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

    // ---- 数据仓库（按账户缓存） ----

    fun data(): DataRepositories = koin.get()
    fun siteConfig(): SiteConfigRepository = koin.get()
    fun files(accountId: String): FileRepository = data().files(accountId)
    fun shares(accountId: String): ShareRepository = data().shares(accountId)
    fun workflows(accountId: String): WorkflowRepository = data().workflows(accountId)
    fun userSettings(accountId: String): UserSettingsRepository = data().userSettings(accountId)
    fun dav(accountId: String): DavRepository = data().dav(accountId)
    fun admin(accountId: String): AdminRepository = data().admin(accountId)
    fun events(accountId: String): EventHub = data().events(accountId)

    // ---- 文件浏览 ViewModel ----

    fun fileListViewModel(accountId: String, uri: String): FileListViewModel =
        koin.get { parametersOf(accountId, uri) }

    fun fileActionViewModel(accountId: String): FileActionViewModel = koin.get { parametersOf(accountId) }

    fun folderPickerViewModel(accountId: String, initialUri: String, excludedUris: List<String>): FolderPickerViewModel =
        koin.get { parametersOf(accountId, initialUri, excludedUris) }

    fun searchViewModel(accountId: String, baseUri: String): SearchViewModel =
        koin.get { parametersOf(accountId, baseUri) }

    fun fileInfoViewModel(accountId: String, uri: String): FileInfoViewModel =
        koin.get { parametersOf(accountId, uri) }

    // ---- 设置、会话交接 ----

    /** 网页会话交接（“在网页中打开”、管理后台、网页预览器） */
    fun webHandoff(): WebHandoff = koin.get()

    fun settingsViewModel(accountId: String): SettingsViewModel = koin.get { parametersOf(accountId) }

    fun securityViewModel(accountId: String): SecurityViewModel = koin.get { parametersOf(accountId) }

    fun davAccountsViewModel(accountId: String): DavAccountsViewModel = koin.get { parametersOf(accountId) }

    fun policiesViewModel(accountId: String): PoliciesViewModel = koin.get { parametersOf(accountId) }

    // ---- 轻量管理 ----

    fun adminSummaryViewModel(accountId: String): AdminSummaryViewModel = koin.get { parametersOf(accountId) }

    fun adminUsersViewModel(accountId: String): AdminUsersViewModel = koin.get { parametersOf(accountId) }

    fun adminUserEditViewModel(accountId: String, userId: Int): AdminUserEditViewModel =
        koin.get { parametersOf(accountId, userId) }

    fun adminSharesViewModel(accountId: String): AdminSharesViewModel = koin.get { parametersOf(accountId) }

    fun adminFilesViewModel(accountId: String): AdminFilesViewModel = koin.get { parametersOf(accountId) }

    fun adminQueueViewModel(accountId: String): AdminQueueViewModel = koin.get { parametersOf(accountId) }

    fun adminNodesViewModel(accountId: String): AdminNodesViewModel = koin.get { parametersOf(accountId) }

    // ---- 预览器 ----

    fun viewerImageViewModel(params: ViewerParams): ViewerImageViewModel = koin.get { parametersOf(params) }

    fun viewerVideoViewModel(params: ViewerParams): ViewerVideoViewModel = koin.get { parametersOf(params) }

    fun viewerAudioViewModel(params: ViewerParams): ViewerAudioViewModel = koin.get { parametersOf(params) }

    fun viewerPdfViewModel(params: ViewerParams): ViewerPdfViewModel = koin.get { parametersOf(params) }

    fun viewerTextViewModel(params: ViewerParams): ViewerTextViewModel = koin.get { parametersOf(params) }

    fun viewerArchiveViewModel(params: ViewerParams): ViewerArchiveViewModel = koin.get { parametersOf(params) }

    fun viewerWebViewModel(params: ViewerParams, darkMode: Boolean): ViewerWebViewModel =
        koin.get { parametersOf(params, darkMode) }
}
