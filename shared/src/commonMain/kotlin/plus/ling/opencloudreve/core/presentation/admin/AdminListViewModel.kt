package plus.ling.opencloudreve.core.presentation.admin

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.model.AdminListRequest
import plus.ling.opencloudreve.core.model.OrderDirection
import plus.ling.opencloudreve.core.model.Pagination
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.handoff.AdminWebSection
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.handoff.attempt
import plus.ling.opencloudreve.core.presentation.handoff.availabilityOrNull

/**
 * 管理端列表的通用状态（用户/分享/文件/任务/节点共用，平台可用同一套列表脚手架）。
 * 分页为页码模式：请求 `page` 从 1 起，响应 `pagination.page` 从 0 起（见 [AdminPaging]）。
 */
data class AdminListState<T : Any>(
    val items: List<T> = emptyList(),
    /** 服务端总数（无结果时为 0） */
    val totalItems: Int = 0,
    val loading: Boolean = false,
    /** 下拉刷新（已有数据时重新加载） */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 已加载页数；下一次请求 `page = loadedPages + 1` */
    val loadedPages: Int = 0,
    val pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
    /** 过滤条件（键见 `AdminConditions`；空值不发送） */
    val conditions: Map<String, String> = emptyMap(),
    /** 空串表示按 id */
    val orderBy: String = "",
    val orderDirection: String = OrderDirection.DESC,
    /** 该列表服务端支持的排序字段（为空表示不可排序，一律按 id） */
    val orderByOptions: List<String> = emptyList(),
    /** 多选的实体 id */
    val selection: Set<Int> = emptySet(),
    val deleting: Boolean = false,
    /** 详情（[AdminListIntent.OpenDetail] 加载） */
    val detail: T? = null,
    val detailLoading: Boolean = false,
    /** 列表加载失败；操作失败通过 [AdminListEffect.Failed] 通知 */
    val error: UiError? = null,
    /** “在网页后台打开”是否可用 */
    val handoff: HandoffAvailability = HandoffAvailability.Checking,
    val openingWeb: Boolean = false,
    /** 节点连通测试 / 获取文件地址等单项操作进行中的实体 id */
    val busyId: Int? = null,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()
}

sealed interface AdminListIntent {
    data object Refresh : AdminListIntent
    data object LoadMore : AdminListIntent

    /** 设置/清除（[value] 为 null 或空串）一个过滤条件，并从第 1 页重新加载 */
    data class SetCondition(val key: String, val value: String?) : AdminListIntent
    data object ClearConditions : AdminListIntent
    data class SetOrder(val orderBy: String, val orderDirection: String) : AdminListIntent
    data class ToggleSelection(val id: Int) : AdminListIntent
    data object SelectAll : AdminListIntent
    data object ClearSelection : AdminListIntent

    /** 批量删除当前选择 */
    data object DeleteSelected : AdminListIntent
    data class Delete(val ids: List<Int>) : AdminListIntent
    data class OpenDetail(val id: Int) : AdminListIntent
    data object CloseDetail : AdminListIntent

    /** 交接后打开网页后台对应页面（[AdminListEffect.OpenUrl]） */
    data object OpenInWeb : AdminListIntent
    data object DismissError : AdminListIntent

    /** 仅文件列表：取主版本下载地址（1 小时有效） */
    data class FetchFileUrl(val id: Int) : AdminListIntent

    /** 仅节点列表：连通测试 */
    data class TestNode(val id: Int) : AdminListIntent

    /** 仅任务队列：刷新队列指标 */
    data object RefreshMetrics : AdminListIntent

    /**
     * 仅任务队列：清理 [notAfterEpochSeconds] 之前创建的任务；[types]/[statuses] 为空时服务端默认三种终态。
     */
    data class CleanupTasks(
        val notAfterEpochSeconds: Long,
        val types: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
    ) : AdminListIntent
}

sealed interface AdminListEffect {
    data class Deleted(val count: Int) : AdminListEffect
    data class Failed(val error: UiError) : AdminListEffect
    data class OpenUrl(val url: String) : AdminListEffect
    data class FileUrlReady(val id: Int, val url: String) : AdminListEffect

    /** 节点测试结果；失败时 [message] 为服务端错误信息 */
    data class NodeTestResult(val id: Int, val success: Boolean, val message: String?) : AdminListEffect
    data object TasksCleaned : AdminListEffect
}

/** 管理端页码分页换算（请求从 1 起，响应从 0 起） */
object AdminPaging {
    const val DEFAULT_PAGE_SIZE = 50

    /** 已加载 [loadedPages] 页后，下一次请求的 `page` */
    fun nextRequestPage(loadedPages: Int): Int = loadedPages + 1

    /** 由响应分页得到已加载页数（响应 `page` 从 0 起） */
    fun loadedPages(pagination: Pagination): Int = pagination.page + 1

    /** 是否还有下一页：按总数计算（总数为 0 时服务端省略 `total_items`） */
    fun hasMore(pagination: Pagination, requestedPageSize: Int): Boolean {
        val total = pagination.totalItems ?: return false
        val size = pagination.pageSize.takeIf { it > 0 } ?: requestedPageSize
        return (pagination.page + 1) * size < total
    }
}

/** 一页结果 */
internal data class AdminPage<T>(val items: List<T>, val pagination: Pagination)

/**
 * 管理端列表 ViewModel 基类：页码分页累积、过滤/排序、多选批量删除、详情、在网页后台打开。
 * 子类只提供取页/详情/删除与实体 id。
 */
abstract class AdminListViewModel<T : Any> internal constructor(
    val accountId: String,
    private val handoff: WebHandoff,
    private val webSection: AdminWebSection,
    orderByOptions: List<String>,
    pageSize: Int,
) : CoreViewModel<AdminListState<T>, AdminListIntent, AdminListEffect>(
    AdminListState(pageSize = pageSize, orderByOptions = orderByOptions),
) {
    private var loadJob: Job? = null

    /** 是否支持批量删除（节点为 false，平台隐藏删除入口） */
    open val supportsDelete: Boolean get() = true

    internal abstract suspend fun fetch(request: AdminListRequest): AdminPage<T>
    internal abstract suspend fun fetchDetail(id: Int): T
    internal abstract suspend fun deleteItems(ids: List<Int>)
    internal abstract fun idOf(item: T): Int

    /** 子类专有意图（文件地址、节点测试、队列指标/清理） */
    internal open fun onExtra(intent: AdminListIntent) {}

    /** 子类在构造完成后调用（避免基类 init 中访问子类尚未初始化的字段） */
    protected fun start() {
        reload(initial = true)
        scope.launch {
            handoff.availabilityOrNull(accountId)?.let { a -> updateState { it.copy(handoff = a) } }
        }
    }

    override fun on(intent: AdminListIntent) {
        when (intent) {
            AdminListIntent.Refresh -> reload(initial = false)
            AdminListIntent.LoadMore -> loadMore()
            is AdminListIntent.SetCondition -> {
                val value = intent.value?.trim()
                updateState { s ->
                    s.copy(conditions = if (value.isNullOrEmpty()) s.conditions - intent.key else s.conditions + (intent.key to value))
                }
                reload(initial = true)
            }
            AdminListIntent.ClearConditions -> {
                updateState { it.copy(conditions = emptyMap()) }
                reload(initial = true)
            }
            is AdminListIntent.SetOrder -> {
                updateState { it.copy(orderBy = intent.orderBy, orderDirection = intent.orderDirection) }
                reload(initial = true)
            }
            is AdminListIntent.ToggleSelection -> updateState { s ->
                s.copy(selection = if (intent.id in s.selection) s.selection - intent.id else s.selection + intent.id)
            }
            AdminListIntent.SelectAll -> updateState { s -> s.copy(selection = s.items.map(::idOf).toSet()) }
            AdminListIntent.ClearSelection -> updateState { it.copy(selection = emptySet()) }
            AdminListIntent.DeleteSelected -> delete(currentState.selection.toList())
            is AdminListIntent.Delete -> delete(intent.ids)
            is AdminListIntent.OpenDetail -> openDetail(intent.id)
            AdminListIntent.CloseDetail -> updateState { it.copy(detail = null, detailLoading = false) }
            AdminListIntent.OpenInWeb -> openInWeb(WebHandoff.adminRedirect(webSection, currentState.detail?.let(::idOf)))
            AdminListIntent.DismissError -> updateState { it.copy(error = null) }
            else -> onExtra(intent)
        }
    }

    private fun request(page: Int): AdminListRequest = currentState.let { s ->
        AdminListRequest(
            page = page,
            pageSize = s.pageSize,
            orderBy = s.orderBy,
            orderDirection = s.orderDirection,
            conditions = s.conditions,
        )
    }

    /** 从第 1 页重新加载；[initial] 为 true 时清空列表显示骨架，否则保留旧数据（下拉刷新） */
    private fun reload(initial: Boolean) {
        loadJob?.cancel()
        updateState { s ->
            if (initial) {
                s.copy(loading = true, refreshing = false, loadingMore = false, items = emptyList(), loadedPages = 0, hasMore = false, error = null, selection = emptySet())
            } else {
                s.copy(refreshing = true, loadingMore = false, error = null)
            }
        }
        val req = request(AdminPaging.nextRequestPage(0))
        loadJob = launchCatching(onError = { e -> updateState { it.copy(loading = false, refreshing = false, error = e) } }) {
            val page = fetch(req)
            updateState { s ->
                val ids = page.items.map(::idOf).toSet()
                s.copy(
                    items = page.items,
                    totalItems = page.pagination.totalItems ?: 0,
                    loadedPages = AdminPaging.loadedPages(page.pagination),
                    hasMore = AdminPaging.hasMore(page.pagination, req.pageSize),
                    loading = false,
                    refreshing = false,
                    selection = s.selection.intersect(ids),
                )
            }
        }
    }

    private fun loadMore() {
        val s = currentState
        if (!s.hasMore || s.loading || s.loadingMore || s.refreshing) return
        updateState { it.copy(loadingMore = true) }
        val req = request(AdminPaging.nextRequestPage(s.loadedPages))
        loadJob = launchCatching(onError = { e ->
            updateState { it.copy(loadingMore = false) }
            sendEffect(AdminListEffect.Failed(e))
        }) {
            val page = fetch(req)
            updateState { cur ->
                val known = cur.items.map(::idOf).toSet()
                cur.copy(
                    items = cur.items + page.items.filterNot { idOf(it) in known },
                    totalItems = page.pagination.totalItems ?: cur.totalItems,
                    loadedPages = AdminPaging.loadedPages(page.pagination),
                    hasMore = AdminPaging.hasMore(page.pagination, req.pageSize),
                    loadingMore = false,
                )
            }
        }
    }

    private fun delete(ids: List<Int>) {
        if (!supportsDelete || ids.isEmpty() || currentState.deleting) return
        updateState { it.copy(deleting = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(deleting = false) }
            sendEffect(AdminListEffect.Failed(e))
            // 部分失败时已删除的项也要从列表消失
            reload(initial = false)
        }) {
            deleteItems(ids)
            updateState { s ->
                s.copy(
                    deleting = false,
                    items = s.items.filterNot { idOf(it) in ids },
                    totalItems = (s.totalItems - ids.size).coerceAtLeast(0),
                    selection = s.selection - ids.toSet(),
                    detail = s.detail?.takeUnless { idOf(it) in ids },
                )
            }
            sendEffect(AdminListEffect.Deleted(ids.size))
        }
    }

    private fun openDetail(id: Int) {
        // 先用列表项占位，详情接口返回后替换（详情比列表多加载关联对象）
        updateState { s -> s.copy(detail = s.items.firstOrNull { idOf(it) == id }, detailLoading = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(detailLoading = false) }
            sendEffect(AdminListEffect.Failed(e))
        }) {
            val d = fetchDetail(id)
            updateState { s -> s.copy(detail = d, detailLoading = false, items = s.items.map { if (idOf(it) == id) d else it }) }
        }
    }

    private fun openInWeb(redirect: String) {
        if (currentState.openingWeb) return
        updateState { it.copy(openingWeb = true) }
        scope.launch {
            val r = handoff.attempt(accountId, redirect)
            updateState { it.copy(openingWeb = false, handoff = r.availability ?: it.handoff) }
            r.url?.let { sendEffect(AdminListEffect.OpenUrl(it)) }
            r.error?.let { sendEffect(AdminListEffect.Failed(it)) }
        }
    }

    /** 子类单项操作的通用包装：设置 [AdminListState.busyId]，失败发 [AdminListEffect.Failed] */
    internal fun runItemOperation(id: Int, block: suspend () -> Unit) {
        if (currentState.busyId != null) return
        updateState { it.copy(busyId = id) }
        launchCatching(onError = { e ->
            updateState { it.copy(busyId = null) }
            sendEffect(AdminListEffect.Failed(e))
        }) {
            block()
            updateState { it.copy(busyId = null) }
        }
    }

    internal fun emit(effect: AdminListEffect) = sendEffect(effect)
}
