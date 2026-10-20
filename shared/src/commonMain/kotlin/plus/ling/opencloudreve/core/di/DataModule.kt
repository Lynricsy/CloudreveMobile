package plus.ling.opencloudreve.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.dsl.module
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.auth.ApiClientRegistry
import plus.ling.opencloudreve.core.auth.AuthEvent
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.device.DavRepository
import plus.ling.opencloudreve.core.events.EventHub
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.presentation.files.FileActionViewModel
import plus.ling.opencloudreve.core.presentation.files.FileInfoViewModel
import plus.ling.opencloudreve.core.presentation.files.FileListViewModel
import plus.ling.opencloudreve.core.presentation.files.FolderPickerViewModel
import plus.ling.opencloudreve.core.presentation.files.SearchViewModel
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.share.ShareRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import plus.ling.opencloudreve.core.workflow.WorkflowRepository
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 按账户缓存的数据仓库集合（同一账户的各页面共享同一实例：缩略图缓存、用户/容量状态、SSE 禁用标记）。
 * 账户登出（[AuthEvent.SignedOut]）时自动清除该账户的实例与站点配置内存缓存。
 */
class DataRepositories internal constructor(
    private val clients: ApiClientProvider,
    private val database: OpenCloudreveDatabase,
    private val accounts: AccountRepository,
    val site: SiteConfigRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = AccountScoped { FileRepository(it, clients, database) }
    private val shares = AccountScoped { ShareRepository(it, clients) }
    private val workflows = AccountScoped { WorkflowRepository(it, clients) }
    private val userSettings = AccountScoped { id ->
        UserSettingsRepository(id, clients) { accountId, user -> accounts.updateProfile(accountId, user) }
    }
    private val dav = AccountScoped { DavRepository(it, clients) }
    private val admin = AccountScoped { AdminRepository(it, clients) }
    private val events = AccountScoped { EventHub(it, clients) }

    init {
        scope.launch {
            accounts.authEvents.collect { event ->
                if (event is AuthEvent.SignedOut) evict(event.accountId)
            }
        }
    }

    fun files(accountId: String): FileRepository = files[accountId]
    fun shares(accountId: String): ShareRepository = shares[accountId]
    fun workflows(accountId: String): WorkflowRepository = workflows[accountId]
    fun userSettings(accountId: String): UserSettingsRepository = userSettings[accountId]
    fun dav(accountId: String): DavRepository = dav[accountId]
    fun admin(accountId: String): AdminRepository = admin[accountId]
    fun events(accountId: String): EventHub = events[accountId]

    /** 丢弃账户的所有缓存实例 */
    fun evict(accountId: String) {
        listOf(files, shares, workflows, userSettings, dav, admin, events).forEach { it.remove(accountId) }
        site.clear(accountId)
    }
}

/** 线程安全的按账户实例缓存（写时复制） */
@OptIn(ExperimentalAtomicApi::class)
internal class AccountScoped<T : Any>(private val create: (String) -> T) {
    private val ref = AtomicReference(emptyMap<String, T>())

    operator fun get(accountId: String): T {
        ref.load()[accountId]?.let { return it }
        val created = create(accountId)
        while (true) {
            val cur = ref.load()
            cur[accountId]?.let { return it }
            if (ref.compareAndSet(cur, cur + (accountId to created))) return created
        }
    }

    fun remove(accountId: String) {
        while (true) {
            val cur = ref.load()
            if (accountId !in cur) return
            if (ref.compareAndSet(cur, cur - accountId)) return
        }
    }
}

/**
 * 数据分片依赖：需要 authModule（`ApiClientProvider`、`ApiClientRegistry`、`OpenCloudreveDatabase`、`AccountRepository`）。
 *
 * 文件浏览 ViewModel 以 `factory` 注册，参数（`parametersOf` 顺序）：
 * - [FileListViewModel]：`(accountId, uri)`
 * - [FileActionViewModel]：`(accountId)`
 * - [FolderPickerViewModel]：`(accountId, initialUri, excludedUris: List<String>)`
 * - [SearchViewModel]：`(accountId, baseUri)`
 * - [FileInfoViewModel]：`(accountId, uri)`
 */
val dataModule = module {
    single { SiteConfigRepository(get(), get<ApiClientRegistry>()::anonymous, get()) }
    single { DataRepositories(get(), get(), get(), get()) }

    factory { (accountId: String, uri: String) ->
        val d = get<DataRepositories>()
        FileListViewModel(accountId, uri, d.files(accountId), d.userSettings(accountId), d.site, d.events(accountId))
    }
    factory { (accountId: String) -> FileActionViewModel(accountId, get<DataRepositories>().files(accountId)) }
    factory { (accountId: String, initialUri: String, excludedUris: List<String>) ->
        FolderPickerViewModel(accountId, initialUri, excludedUris, get<DataRepositories>().files(accountId))
    }
    factory { (accountId: String, baseUri: String) ->
        val d = get<DataRepositories>()
        SearchViewModel(accountId, baseUri, d.files(accountId), d.site)
    }
    factory { (accountId: String, uri: String) ->
        val d = get<DataRepositories>()
        FileInfoViewModel(accountId, uri, d.files(accountId), d.site)
    }
}
