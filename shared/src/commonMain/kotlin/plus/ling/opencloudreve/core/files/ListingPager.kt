package plus.ling.opencloudreve.core.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.model.ExplorerView
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.ListPage
import plus.ling.opencloudreve.core.model.NavigatorProps
import plus.ling.opencloudreve.core.model.Pagination
import plus.ling.opencloudreve.core.model.StoragePolicy

/** 分页累积后的目录状态 */
data class ListingSnapshot(
    val uri: String,
    val files: List<FileItem> = emptyList(),
    /** 当前目录自身（根的零值对象 id 为空） */
    val parent: FileItem? = null,
    val props: NavigatorProps = NavigatorProps(),
    /** `X-Cr-Context-Hint`，后续取 URL/缩略图时带回 */
    val contextHint: String? = null,
    val storagePolicy: StoragePolicy? = null,
    /** 服务端同步的视图设置 */
    val view: ExplorerView? = null,
    val singleFileView: Boolean = false,
    val mixedType: Boolean = false,
    val recursionLimitReached: Boolean = false,
    val hasMore: Boolean = false,
    /** 已加载页数；0 表示尚未加载（可能是缓存预填） */
    val loadedPages: Int = 0,
    /** 内容来自 `ListingCache` 而非网络 */
    val fromCache: Boolean = false,
)

/**
 * 目录分页累积器：游标模式回传 `next_token`；`is_cursor` 缺失且有 `total_items` 时按 `page` 递增。
 *
 * [refresh] 重新从第一页加载；[loadMore] 追加下一页（按 path 去重）。两者互斥串行。
 * 每次加载后把累积结果写入 `ListingCache`（搜索除外）。
 */
class ListingPager internal constructor(
    private val repository: FileRepository,
    val uri: String,
    pageSize: Int?,
    private val orderBy: String?,
    private val orderDirection: String?,
) {
    private val pageSize: Int? = pageSize?.coerceAtLeast(ExplorerView.MIN_PAGE_SIZE)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ListingSnapshot(uri))
    private var nextToken: String? = null
    private var nextPage: Int? = null

    val state: StateFlow<ListingSnapshot> = mutableState.asStateFlow()

    /** 尚未加载时用 `ListingCache` 预填（不发请求）；返回是否命中 */
    @Throws(Exception::class)
    suspend fun primeFromCache(): Boolean = mutex.withLock {
        if (mutableState.value.loadedPages > 0) return false
        val cached = repository.cachedListing(uri) ?: return false
        mutableState.value = snapshotOf(cached, cached.files, pages = 0).copy(fromCache = true, hasMore = false)
        true
    }

    /** 从第一页重新加载 */
    @Throws(Exception::class)
    suspend fun refresh(): ListingSnapshot = mutex.withLock {
        val page = repository.listPage(uri, pageSize, orderBy, orderDirection)
        advanceCursor(page.pagination)
        val snapshot = snapshotOf(page, page.files, pages = 1)
        mutableState.value = snapshot
        snapshot
    }

    /** 追加下一页；没有更多时直接返回当前状态。尚未加载过时等同 [refresh] */
    @Throws(Exception::class)
    suspend fun loadMore(): ListingSnapshot {
        if (mutableState.value.loadedPages == 0) return refresh()
        return mutex.withLock {
            val current = mutableState.value
            val token = nextToken
            val pageNo = nextPage
            if (token == null && pageNo == null) return@withLock current
            val page = repository.listPage(uri, pageSize, orderBy, orderDirection, pageNo, token)
            advanceCursor(page.pagination)
            val seen = current.files.mapTo(HashSet()) { it.path }
            val merged = current.files + page.files.filter { seen.add(it.path) }
            val snapshot = snapshotOf(page, merged, pages = current.loadedPages + 1)
            mutableState.value = snapshot
            repository.writeListingCache(uri, page.copy(files = merged, pagination = Pagination()))
            snapshot
        }
    }

    /** 加载全部剩余页（系统文件集成枚举用），最多 [maxPages] 页 */
    @Throws(Exception::class)
    suspend fun loadAll(maxPages: Int = 1000): ListingSnapshot {
        var snapshot = if (mutableState.value.loadedPages == 0) refresh() else mutableState.value
        var pages = 1
        while (snapshot.hasMore && pages < maxPages) {
            snapshot = loadMore()
            pages++
        }
        return snapshot
    }

    /** 本地修改已加载的列表（如操作成功后先移除被删项，再异步刷新） */
    fun updateFiles(transform: (List<FileItem>) -> List<FileItem>) {
        val cur = mutableState.value
        mutableState.value = cur.copy(files = transform(cur.files))
    }

    private fun advanceCursor(p: Pagination) {
        when {
            !p.nextToken.isNullOrEmpty() -> {
                nextToken = p.nextToken
                nextPage = null
            }
            !p.isCursor && p.totalItems != null && p.hasMore -> {
                nextToken = null
                nextPage = p.page + 1
            }
            else -> {
                nextToken = null
                nextPage = null
            }
        }
    }

    private fun snapshotOf(page: ListPage, files: List<FileItem>, pages: Int) = ListingSnapshot(
        uri = uri,
        files = files,
        parent = page.parent,
        props = page.props,
        contextHint = page.contextHint,
        storagePolicy = page.storagePolicy,
        view = page.view,
        singleFileView = page.singleFileView,
        mixedType = page.mixedType,
        recursionLimitReached = page.recursionLimitReached,
        hasMore = nextToken != null || nextPage != null,
        loadedPages = pages,
    )
}
