package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.events.EventHub
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.files.ListingPager
import plus.ling.opencloudreve.core.files.ListingSnapshot
import plus.ling.opencloudreve.core.files.ViewerResolver
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.Capacity
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.ExplorerView
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileOrderBy
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.OrderDirection
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 文件列表页状态 */
data class FileListState(
    val uri: String,
    /** 目录名；根目录为空串（平台显示“我的文件”/“回收站”等） */
    val title: String,
    /** 文件系统：`my`/`trash`/`share`/`shared_with_me` */
    val fs: String,
    val isRoot: Boolean,
    val isSearch: Boolean,
    val files: List<FileItem> = emptyList(),
    /** 当前目录自身 */
    val parent: FileItem? = null,
    /** 首次加载（无内容时显示骨架） */
    val loading: Boolean = false,
    /** 下拉刷新中（已有内容） */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 加载失败且没有可显示内容时的整页错误 */
    val error: UiError? = null,
    /** 当前内容来自离线缓存 */
    val fromCache: Boolean = false,
    /** 目录能力位（`props.capability`） */
    val capability: BoolSet? = null,
    val orderBy: String = FileOrderBy.CREATED_AT,
    val orderDirection: String = OrderDirection.ASC,
    /** 为空表示不可排序（搜索/分享） */
    val orderByOptions: List<String> = emptyList(),
    val orderDirectionOptions: List<String> = emptyList(),
    /** `list` / `grid` / `gallery` */
    val viewMode: String = ExplorerView.VIEW_GRID,
    val showThumbnails: Boolean = true,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val selecting: Boolean = false,
    /** 已选文件的 path */
    val selection: Set<String> = emptySet(),
    val storagePolicy: StoragePolicy? = null,
    val contextHint: String? = null,
    /** 单文件分享根：直接进入预览 */
    val singleFileView: Boolean = false,
    val user: User? = null,
    val capacity: Capacity? = null,
    /** 批量操作上限（`max_batch_size`，0 表示未知） */
    val maxBatchSize: Int = 0,
    /** 打开文件时的预览器选择（站点配置加载后可用） */
    val viewerResolver: ViewerResolver? = null,
) {
    val canSort: Boolean get() = orderByOptions.isNotEmpty()
    val canCreate: Boolean get() = capability?.has(NavigatorCapability.CREATE_FILE) == true
    val canUpload: Boolean get() = capability?.has(NavigatorCapability.UPLOAD_FILE) == true
    val isTrash: Boolean get() = fs == CrUri.FS_TRASH
    val selectedFiles: List<FileItem> get() = files.filter { it.path in selection }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
    }
}

sealed interface FileListIntent {
    data object Refresh : FileListIntent
    data object LoadMore : FileListIntent

    /** 修改排序（同步到服务端视图，除非用户关闭了视图同步） */
    data class SetSort(val orderBy: String, val orderDirection: String) : FileListIntent
    data class SetViewMode(val viewMode: String) : FileListIntent
    data class SetThumbnails(val enabled: Boolean) : FileListIntent

    data object EnterSelection : FileListIntent
    data class ToggleSelection(val path: String) : FileListIntent
    data object SelectAll : FileListIntent
    data object ExitSelection : FileListIntent

    /** 页面前后台切换：前台订阅目录事件，进入后台 10 秒后断开 */
    data class SetForeground(val foreground: Boolean) : FileListIntent

    /** 操作成功后本地移除条目（随后自动刷新） */
    data class RemoveLocally(val paths: List<String>) : FileListIntent
}

sealed interface FileListEffect {
    /** 非致命错误（加载更多失败、视图同步失败、刷新失败但保留旧内容） */
    data class ShowError(val error: UiError) : FileListEffect
}

/**
 * 文件列表（目录、回收站、与我共享、分享内目录、搜索结果）。
 * 构造后立即加载（先用离线缓存预填）；前台时订阅 SSE，事件防抖 1 秒后刷新。
 */
class FileListViewModel(
    val accountId: String,
    uri: String,
    private val files: FileRepository,
    private val userSettings: UserSettingsRepository,
    private val site: SiteConfigRepository,
    private val events: EventHub,
    private val eventDebounce: Duration = 1.seconds,
    private val backgroundGrace: Duration = 10.seconds,
) : CoreViewModel<FileListState, FileListIntent, FileListEffect>(initialState(uri)) {

    private val crUri = CrUri.parse(uri)
    private var pager: ListingPager = files.pager(uri)
    private var loadJob: Job? = null
    private var eventsJob: Job? = null
    private var backgroundJob: Job? = null
    private var debounceJob: Job? = null
    private var localViewSet = false
    private var serverView: ExplorerView? = null

    init {
        scope.launch { userSettings.user.collect { u -> updateState { it.copy(user = u) } } }
        scope.launch { userSettings.capacity.collect { c -> updateState { it.copy(capacity = c) } } }
        scope.launch { loadContext() }
        initialLoad()
    }

    override fun on(intent: FileListIntent) {
        when (intent) {
            FileListIntent.Refresh -> reload(showRefreshing = true)
            FileListIntent.LoadMore -> loadMore()
            is FileListIntent.SetSort -> changeView(
                orderBy = intent.orderBy,
                orderDirection = intent.orderDirection,
                reloadNeeded = true,
            )
            is FileListIntent.SetViewMode -> changeView(viewMode = intent.viewMode)
            is FileListIntent.SetThumbnails -> changeView(thumbnails = intent.enabled)
            FileListIntent.EnterSelection -> updateState { it.copy(selecting = true) }
            is FileListIntent.ToggleSelection -> updateState {
                val next = if (intent.path in it.selection) it.selection - intent.path else it.selection + intent.path
                it.copy(selecting = true, selection = next)
            }
            FileListIntent.SelectAll -> updateState { s -> s.copy(selecting = true, selection = s.files.mapTo(HashSet()) { it.path }) }
            FileListIntent.ExitSelection -> updateState { it.copy(selecting = false, selection = emptySet()) }
            is FileListIntent.SetForeground -> setForeground(intent.foreground)
            is FileListIntent.RemoveLocally -> {
                val removed = intent.paths.toSet()
                pager.updateFiles { list -> list.filterNot { it.path in removed } }
                updateState { s -> s.copy(files = s.files.filterNot { it.path in removed }, selection = s.selection - removed) }
                reload(showRefreshing = false)
            }
        }
    }

    /** 缩略图地址（带当前目录的 context hint）；无缩略图返回 null */
    @Throws(Exception::class)
    suspend fun thumbnailUrl(file: FileItem): String? =
        if (currentState.showThumbnails) files.thumbUrl(file, currentState.contextHint) else null

    /** 进入子目录时用的 URI（目录的 path） */
    fun childUri(folder: FileItem): String = folder.path

    private fun initialLoad() {
        loadJob = scope.launch {
            updateState { it.copy(loading = true, error = null) }
            try {
                if (pager.primeFromCache()) applySnapshot(pager.state.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 缓存损坏忽略
            }
            fetchFirstPage(showRefreshing = false)
        }
    }

    private fun reload(showRefreshing: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch { fetchFirstPage(showRefreshing) }
    }

    private suspend fun fetchFirstPage(showRefreshing: Boolean) {
        updateState {
            it.copy(
                loading = it.files.isEmpty(),
                refreshing = showRefreshing && it.files.isNotEmpty(),
                error = null,
            )
        }
        try {
            applySnapshot(pager.refresh())
            if (crUri.fs == CrUri.FS_MY && !crUri.isSearch()) refreshCapacityQuietly()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = e.toUiError()
            val hasContent = currentState.files.isNotEmpty()
            updateState { it.copy(loading = false, refreshing = false, error = if (hasContent) null else err) }
            if (hasContent) sendEffect(FileListEffect.ShowError(err))
        }
    }

    private fun loadMore() {
        val s = currentState
        if (!s.hasMore || s.loadingMore || s.loading) return
        scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                applySnapshot(pager.loadMore())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false) }
                sendEffect(FileListEffect.ShowError(e.toUiError()))
            }
        }
    }

    private fun applySnapshot(snapshot: ListingSnapshot) {
        // 用户本地改过视图后不再被服务端（可能尚未同步的）视图覆盖
        snapshot.view?.let { serverView = it }
        val view = if (localViewSet) null else snapshot.view
        updateState { s ->
            val paths = snapshot.files.mapTo(HashSet()) { it.path }
            s.copy(
                files = snapshot.files,
                parent = snapshot.parent,
                loading = false,
                refreshing = false,
                loadingMore = false,
                hasMore = snapshot.hasMore,
                error = null,
                fromCache = snapshot.fromCache,
                capability = snapshot.props.capability ?: snapshot.parent?.capability,
                orderByOptions = snapshot.props.orderByOptions.orEmpty(),
                orderDirectionOptions = snapshot.props.orderDirectionOptions.orEmpty(),
                orderBy = view?.order ?: s.orderBy,
                orderDirection = view?.orderDirection ?: s.orderDirection,
                viewMode = view?.view ?: s.viewMode,
                showThumbnails = view?.thumbnail ?: s.showThumbnails,
                pageSize = view?.pageSize?.takeIf { it > 0 } ?: s.pageSize,
                storagePolicy = snapshot.storagePolicy,
                contextHint = snapshot.contextHint,
                singleFileView = snapshot.singleFileView,
                selection = s.selection.filterTo(HashSet()) { it in paths },
            )
        }
    }

    /** 用户信息与站点 explorer 配置（预览器、批量上限）；失败不影响列表 */
    private suspend fun loadContext() {
        val user = try {
            userSettings.me()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val explorer = try {
            site.explorer(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            site.cachedExplorer(accountId)
        } ?: return
        updateState {
            it.copy(
                maxBatchSize = explorer.maxBatchSize,
                viewerResolver = ViewerResolver(explorer, user?.group?.permission),
            )
        }
    }

    private suspend fun refreshCapacityQuietly() {
        try {
            userSettings.refreshCapacity()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 容量拉取失败不影响列表
        }
    }

    private fun changeView(
        orderBy: String? = null,
        orderDirection: String? = null,
        viewMode: String? = null,
        thumbnails: Boolean? = null,
        reloadNeeded: Boolean = false,
    ) {
        localViewSet = true
        updateState {
            it.copy(
                orderBy = orderBy ?: it.orderBy,
                orderDirection = orderDirection ?: it.orderDirection,
                viewMode = viewMode ?: it.viewMode,
                showThumbnails = thumbnails ?: it.showThumbnails,
            )
        }
        val s = currentState
        if (reloadNeeded) {
            pager = files.pager(s.uri, s.pageSize, s.orderBy, s.orderDirection)
            reload(showRefreshing = true)
        }
        syncView(s)
    }

    /** 视图设置同步：仅“我的文件”的非搜索目录，且用户未关闭视图同步 */
    private fun syncView(s: FileListState) {
        if (crUri.fs != CrUri.FS_MY || crUri.isSearch()) return
        if (s.user?.disableViewSync == true) return
        val base = serverView ?: ExplorerView()
        val view = base.copy(
            pageSize = s.pageSize.coerceAtLeast(ExplorerView.MIN_PAGE_SIZE),
            order = s.orderBy,
            orderDirection = s.orderDirection,
            view = s.viewMode,
            thumbnail = s.showThumbnails,
            // 必须带合法的 gallery_width，否则 PATCH /file/view 返回 40001（GalleryWidth too short）
            galleryWidth = base.galleryWidth
                ?.takeIf { it in ExplorerView.MIN_GALLERY_WIDTH..ExplorerView.MAX_GALLERY_WIDTH }
                ?: ExplorerView.DEFAULT_GALLERY_WIDTH,
        )
        scope.launch {
            try {
                files.patchView(s.uri, view)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(FileListEffect.ShowError(e.toUiError()))
            }
        }
    }

    private fun setForeground(value: Boolean) {
        if (value) {
            backgroundJob?.cancel()
            backgroundJob = null
            if (eventsJob?.isActive != true) startEvents()
        } else {
            backgroundJob?.cancel()
            backgroundJob = scope.launch {
                delay(backgroundGrace)
                eventsJob?.cancel()
                eventsJob = null
            }
        }
    }

    private fun startEvents() {
        if (crUri.fs != CrUri.FS_MY || crUri.isSearch() || events.isDisabled) return
        eventsJob = scope.launch {
            events.subscribe(currentState.uri).collect {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(eventDebounce)
                    reload(showRefreshing = false)
                }
            }
        }
    }

    private companion object {
        fun initialState(uri: String): FileListState {
            val u = CrUri.parse(uri)
            return FileListState(
                uri = uri,
                title = u.name(),
                fs = u.fs,
                isRoot = u.isRoot(),
                isSearch = u.isSearch(),
            )
        }
    }
}
