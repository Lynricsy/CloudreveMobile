package plus.ling.opencloudreve.core.presentation.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.model.OrderDirection
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.model.ShareOrderBy
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.share.ShareRepository

data class ShareListState(
    val shares: List<Share> = emptyList(),
    /** 首次加载（无内容） */
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 加载失败且无内容时的整页错误 */
    val error: UiError? = null,
    /** 排序字段：null（按创建）、`views`、`downloads`、`remain_downloads` */
    val orderBy: String? = null,
    val orderDirection: String = OrderDirection.DESC,
    val selecting: Boolean = false,
    /** 已选分享 id */
    val selection: Set<String> = emptySet(),
    val deleting: Boolean = false,
) {
    val isEmpty: Boolean get() = shares.isEmpty() && !loading && error == null

    /** 可选排序字段（null 表示默认按创建顺序） */
    val orderByOptions: List<String?> get() = ORDER_BY_OPTIONS

    companion object {
        val ORDER_BY_OPTIONS: List<String?> =
            listOf(null, ShareOrderBy.VIEWS, ShareOrderBy.DOWNLOADS, ShareOrderBy.REMAIN_DOWNLOADS)
        const val PAGE_SIZE = 50
    }
}

sealed interface ShareListIntent {
    data object Refresh : ShareListIntent
    data object LoadMore : ShareListIntent
    data class SetSort(val orderBy: String?, val orderDirection: String) : ShareListIntent
    data class ToggleSelection(val id: String) : ShareListIntent
    data object SelectAll : ShareListIntent
    data object ExitSelection : ShareListIntent

    /** 删除（单个走 `DELETE /share/:id`，多个走批量） */
    data class Delete(val ids: List<String>) : ShareListIntent

    /** 跳转到源文件：取 `source_uri`（`owner_extended=true`）后发 [ShareListEffect.OpenSource] */
    data class OpenSource(val id: String) : ShareListIntent
}

sealed interface ShareListEffect {
    data class Deleted(val count: Int) : ShareListEffect

    /** 源文件 URI（平台打开其所在目录或文件信息） */
    data class OpenSource(val shareId: String, val sourceUri: String) : ShareListEffect
    data class Failed(val error: UiError) : ShareListEffect
}

/** 我的分享（分享 Tab）：游标分页、排序、批量删除、跳转源文件 */
class ShareListViewModel(
    val accountId: String,
    private val shares: ShareRepository,
) : CoreViewModel<ShareListState, ShareListIntent, ShareListEffect>(ShareListState()) {

    private var nextToken: String? = null
    private var loadJob: Job? = null

    init {
        reload(refreshing = false)
    }

    override fun on(intent: ShareListIntent) {
        when (intent) {
            ShareListIntent.Refresh -> reload(refreshing = true)
            ShareListIntent.LoadMore -> loadMore()
            is ShareListIntent.SetSort -> {
                updateState { it.copy(orderBy = intent.orderBy, orderDirection = intent.orderDirection) }
                reload(refreshing = false)
            }
            is ShareListIntent.ToggleSelection -> updateState {
                val next = if (intent.id in it.selection) it.selection - intent.id else it.selection + intent.id
                it.copy(selecting = true, selection = next)
            }
            ShareListIntent.SelectAll -> updateState { s -> s.copy(selecting = true, selection = s.shares.mapTo(HashSet()) { it.id }) }
            ShareListIntent.ExitSelection -> updateState { it.copy(selecting = false, selection = emptySet()) }
            is ShareListIntent.Delete -> delete(intent.ids)
            is ShareListIntent.OpenSource -> openSource(intent.id)
        }
    }

    private fun reload(refreshing: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            updateState { it.copy(loading = it.shares.isEmpty(), refreshing = refreshing && it.shares.isNotEmpty(), error = null) }
            try {
                val s = currentState
                val page = shares.list(ShareListState.PAGE_SIZE, null, s.orderBy, s.orderDirection)
                nextToken = page.pagination.nextToken
                updateState {
                    it.copy(
                        shares = page.shares,
                        loading = false,
                        refreshing = false,
                        hasMore = page.pagination.hasMore,
                        selection = it.selection intersect page.shares.mapTo(HashSet()) { sh -> sh.id },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.toUiError()
                updateState { it.copy(loading = false, refreshing = false, error = if (it.shares.isEmpty()) err else null) }
                if (currentState.shares.isNotEmpty()) sendEffect(ShareListEffect.Failed(err))
            }
        }
    }

    private fun loadMore() {
        val s = currentState
        val token = nextToken
        if (!s.hasMore || s.loadingMore || s.loading || token == null) return
        loadJob = scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                val page = shares.list(ShareListState.PAGE_SIZE, token, s.orderBy, s.orderDirection)
                nextToken = page.pagination.nextToken
                updateState { st ->
                    val known = st.shares.mapTo(HashSet()) { it.id }
                    st.copy(
                        shares = st.shares + page.shares.filter { it.id !in known },
                        loadingMore = false,
                        hasMore = page.pagination.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false) }
                sendEffect(ShareListEffect.Failed(e.toUiError()))
            }
        }
    }

    private fun delete(ids: List<String>) {
        if (ids.isEmpty() || currentState.deleting) return
        scope.launch {
            updateState { it.copy(deleting = true) }
            try {
                shares.delete(ids)
                val removed = ids.toSet()
                updateState { s ->
                    s.copy(
                        deleting = false,
                        shares = s.shares.filterNot { it.id in removed },
                        selection = s.selection - removed,
                        selecting = s.selecting && (s.selection - removed).isNotEmpty(),
                    )
                }
                sendEffect(ShareListEffect.Deleted(ids.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(deleting = false) }
                sendEffect(ShareListEffect.Failed(e.toUiError()))
                reload(refreshing = false)
            }
        }
    }

    private fun openSource(id: String) {
        scope.launch {
            try {
                // 带密码的分享解析 source_uri 时服务端会校验密码（列表中所有者可见密码）
                val password = currentState.shares.firstOrNull { it.id == id }?.password?.ifEmpty { null }
                val info = shares.info(id, password = password, ownerExtended = true)
                val uri = info.sourceUri
                if (uri.isNullOrEmpty()) {
                    sendEffect(ShareListEffect.Failed(UiError(ErrorKind.NotFound)))
                } else {
                    sendEffect(ShareListEffect.OpenSource(id, uri))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(ShareListEffect.Failed(e.toUiError()))
            }
        }
    }
}
