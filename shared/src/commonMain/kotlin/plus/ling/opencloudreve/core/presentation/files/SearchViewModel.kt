package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.files.ListingPager
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FullTextHit
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.SearchFileType
import plus.ling.opencloudreve.core.model.SearchParams
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/** 高级搜索过滤（时间为 Unix 秒，大小为字节） */
data class SearchFilters(
    val type: SearchFileType? = null,
    /** `image` / `video` / `audio` / `document` */
    val category: String? = null,
    val sizeGte: Long? = null,
    val sizeLte: Long? = null,
    val createdGte: Long? = null,
    val createdLte: Long? = null,
    val updatedGte: Long? = null,
    val updatedLte: Long? = null,
    /** 标签名（按 `meta_tag:<名>` 匹配） */
    val tags: List<String> = emptyList(),
    val caseFolding: Boolean = true,
) {
    val isEmpty: Boolean
        get() = type == null && category == null && sizeGte == null && sizeLte == null &&
            createdGte == null && createdLte == null && updatedGte == null && updatedLte == null && tags.isEmpty()

    companion object {
        const val CATEGORY_IMAGE = "image"
        const val CATEGORY_VIDEO = "video"
        const val CATEGORY_AUDIO = "audio"
        const val CATEGORY_DOCUMENT = "document"
    }
}

data class SearchState(
    /** 搜索范围（目录 URI，不含搜索参数） */
    val baseUri: String,
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    /** 全文搜索模式（站点 `full_text_search` 为真才可开启） */
    val fullText: Boolean = false,
    val fullTextAvailable: Boolean = false,
    /** 已提交的搜索 URI（名称/过滤模式） */
    val searchUri: String? = null,
    val results: List<FileItem> = emptyList(),
    val fullTextHits: List<FullTextHit> = emptyList(),
    val fullTextTotal: Long = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val submitted: Boolean = false,
    val error: UiError? = null,
    val history: List<String> = emptyList(),
    val contextHint: String? = null,
) {
    val canSubmit: Boolean get() = query.isNotBlank() || (!fullText && !filters.isEmpty)
}

sealed interface SearchIntent {
    data class SetQuery(val query: String) : SearchIntent
    data class SetFilters(val filters: SearchFilters) : SearchIntent
    data class SetFullText(val enabled: Boolean) : SearchIntent
    data object Submit : SearchIntent
    data object LoadMore : SearchIntent

    /** 用历史关键词直接搜索 */
    data class UseHistory(val query: String) : SearchIntent
    data class DeleteHistory(val query: String) : SearchIntent
    data object ClearHistory : SearchIntent
    data object Clear : SearchIntent
}

/**
 * 搜索：名称/高级过滤通过 `cloudreve://…?name=…` 搜索 URI 列出（游标分页、排序固定）；
 * 全文搜索调用 `GET /file/search`（偏移分页）。提交时记录搜索历史。
 */
class SearchViewModel(
    val accountId: String,
    baseUri: String,
    private val files: FileRepository,
    private val site: SiteConfigRepository,
) : CoreViewModel<SearchState, SearchIntent, Nothing>(SearchState(baseUri = CrUri.parse(baseUri).withoutSearch().toString())) {

    private var pager: ListingPager? = null
    private var searchJob: Job? = null

    init {
        scope.launch { reloadHistory() }
        scope.launch {
            val available = try {
                site.explorer(accountId).fullTextSearch
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                site.cachedExplorer(accountId)?.fullTextSearch ?: false
            }
            updateState { it.copy(fullTextAvailable = available, fullText = it.fullText && available) }
        }
    }

    override fun on(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.SetQuery -> updateState { it.copy(query = intent.query) }
            is SearchIntent.SetFilters -> updateState { it.copy(filters = intent.filters) }
            is SearchIntent.SetFullText -> updateState { it.copy(fullText = intent.enabled && it.fullTextAvailable) }
            SearchIntent.Submit -> submit()
            SearchIntent.LoadMore -> loadMore()
            is SearchIntent.UseHistory -> {
                updateState { it.copy(query = intent.query) }
                submit()
            }
            is SearchIntent.DeleteHistory -> scope.launch {
                runQuietly { files.deleteSearch(intent.query) }
                reloadHistory()
            }
            SearchIntent.ClearHistory -> scope.launch {
                runQuietly { files.clearSearchHistory() }
                reloadHistory()
            }
            SearchIntent.Clear -> {
                searchJob?.cancel()
                pager = null
                updateState {
                    SearchState(baseUri = it.baseUri, fullTextAvailable = it.fullTextAvailable, history = it.history)
                }
            }
        }
    }

    /** 按当前查询与过滤构建搜索 URI */
    fun buildSearchUri(): String {
        val s = currentState
        val f = s.filters
        val names = s.query.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        val params = SearchParams(
            names = names,
            caseFolding = f.caseFolding,
            category = f.category,
            type = f.type,
            metadata = f.tags.associate { MetadataKeys.TAG_PREFIX + it to "" },
            sizeGte = f.sizeGte,
            sizeLte = f.sizeLte,
            createdGte = f.createdGte,
            createdLte = f.createdLte,
            updatedGte = f.updatedGte,
            updatedLte = f.updatedLte,
        )
        return CrUri.parse(s.baseUri).withSearch(params).toString()
    }

    private fun submit() {
        val s = currentState
        if (!s.canSubmit) return
        searchJob?.cancel()
        val query = s.query.trim()
        searchJob = scope.launch {
            if (query.isNotEmpty()) {
                runQuietly { files.saveSearch(query) }
                reloadHistory()
            }
            updateState {
                it.copy(
                    loading = true,
                    submitted = true,
                    error = null,
                    results = emptyList(),
                    fullTextHits = emptyList(),
                    hasMore = false,
                )
            }
            try {
                if (s.fullText) {
                    val r = files.fullTextSearch(query, 0)
                    pager = null
                    updateState {
                        it.copy(
                            loading = false,
                            searchUri = null,
                            fullTextHits = r.hits,
                            fullTextTotal = r.total,
                            hasMore = r.hits.size < r.total,
                        )
                    }
                } else {
                    val uri = buildSearchUri()
                    val p = files.pager(uri)
                    pager = p
                    val snap = p.refresh()
                    updateState {
                        it.copy(
                            loading = false,
                            searchUri = uri,
                            results = snap.files,
                            hasMore = snap.hasMore,
                            contextHint = snap.contextHint,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    private fun loadMore() {
        val s = currentState
        if (!s.hasMore || s.loading || s.loadingMore) return
        searchJob = scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                val p = pager
                if (p != null) {
                    val snap = p.loadMore()
                    updateState { it.copy(loadingMore = false, results = snap.files, hasMore = snap.hasMore) }
                } else {
                    val r = files.fullTextSearch(s.query.trim(), s.fullTextHits.size)
                    updateState {
                        val hits = it.fullTextHits + r.hits
                        it.copy(
                            loadingMore = false,
                            fullTextHits = hits,
                            fullTextTotal = r.total,
                            hasMore = r.hits.isNotEmpty() && hits.size < r.total,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false, error = e.toUiError()) }
            }
        }
    }

    private suspend fun reloadHistory() {
        val history = try {
            files.searchHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        updateState { it.copy(history = history) }
    }

    private suspend inline fun runQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 搜索历史读写失败不影响搜索
        }
    }
}
