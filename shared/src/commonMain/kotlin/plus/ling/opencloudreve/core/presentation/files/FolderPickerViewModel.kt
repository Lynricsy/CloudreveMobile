package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.files.ListingPager
import plus.ling.opencloudreve.core.files.ListingSnapshot
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError

/** 面包屑一级 */
data class PathCrumb(val name: String, val uri: String)

data class FolderPickerState(
    val uri: String,
    val crumbs: List<PathCrumb>,
    /** 当前目录下的子目录 */
    val folders: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: UiError? = null,
    val capability: BoolSet? = null,
    /** 当前目录是被移动的对象本身或其子目录 */
    val excluded: Boolean = false,
    val creating: Boolean = false,
) {
    val canCreateFolder: Boolean get() = capability?.has(NavigatorCapability.CREATE_FILE) == true
    val canGoUp: Boolean get() = crumbs.size > 1

    /** 可选为目标：可写且不在排除范围内 */
    val canSelect: Boolean get() = canCreateFolder && !excluded && !loading && error == null
}

sealed interface FolderPickerIntent {
    data class Open(val uri: String) : FolderPickerIntent
    data object Up : FolderPickerIntent
    data object Refresh : FolderPickerIntent
    data object LoadMore : FolderPickerIntent

    /** 在当前目录新建文件夹并进入 */
    data class CreateFolder(val name: String) : FolderPickerIntent
    data object DismissError : FolderPickerIntent
}

/**
 * 目录选择器（移动/复制目标、上传目标、WebDAV 根目录、分享保存位置）。
 * @param excludedUris 不能作为目标的目录（被移动的目录本身，其子目录同样排除）
 */
class FolderPickerViewModel(
    val accountId: String,
    initialUri: String,
    excludedUris: List<String>,
    private val files: FileRepository,
) : CoreViewModel<FolderPickerState, FolderPickerIntent, Nothing>(stateFor(initialUri, excludedUris)) {

    private val excluded = excludedUris.mapNotNull { CrUri.parseOrNull(it) }
    private var pager: ListingPager = files.pager(initialUri)
    private var loadJob: Job? = null

    init {
        load()
    }

    override fun on(intent: FolderPickerIntent) {
        when (intent) {
            is FolderPickerIntent.Open -> open(intent.uri)
            FolderPickerIntent.Up -> currentState.crumbs.dropLast(1).lastOrNull()?.let { open(it.uri) }
            FolderPickerIntent.Refresh -> load()
            FolderPickerIntent.LoadMore -> loadMore()
            is FolderPickerIntent.CreateFolder -> createFolder(intent.name)
            FolderPickerIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun open(uri: String) {
        pager = files.pager(uri)
        updateState { stateForParsed(uri, excluded) }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            updateState { it.copy(loading = true, error = null) }
            try {
                apply(pager.refresh())
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
        scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                apply(pager.loadMore())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false, error = e.toUiError()) }
            }
        }
    }

    private fun apply(snapshot: ListingSnapshot) {
        updateState {
            it.copy(
                folders = snapshot.files.filter { f -> f.isFolder && !f.isSymbolic },
                hasMore = snapshot.hasMore,
                capability = snapshot.props.capability ?: snapshot.parent?.capability,
                loading = false,
                loadingMore = false,
            )
        }
    }

    private fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || currentState.creating) return
        scope.launch {
            updateState { it.copy(creating = true) }
            try {
                val created = files.createFolder(currentState.uri, trimmed)
                updateState { it.copy(creating = false) }
                open(created.path.ifEmpty { FileRepository.childUri(currentState.uri, trimmed) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(creating = false, error = e.toUiError()) }
            }
        }
    }

    private companion object {
        fun stateFor(uri: String, excludedUris: List<String>): FolderPickerState =
            stateForParsed(uri, excludedUris.mapNotNull { CrUri.parseOrNull(it) })

        fun stateForParsed(uri: String, excluded: List<CrUri>): FolderPickerState {
            val u = CrUri.parse(uri)
            val crumbs = ArrayList<PathCrumb>()
            var cur: CrUri? = u
            while (cur != null) {
                crumbs += PathCrumb(cur.name(), cur.toString())
                cur = cur.parent()
            }
            crumbs.reverse()
            return FolderPickerState(
                uri = uri,
                crumbs = crumbs,
                excluded = excluded.any { ex -> isSameOrDescendant(u, ex) },
            )
        }

        fun isSameOrDescendant(target: CrUri, ancestor: CrUri): Boolean =
            target.fs == ancestor.fs && target.id == ancestor.id &&
                target.segments.size >= ancestor.segments.size &&
                target.segments.subList(0, ancestor.segments.size) == ancestor.segments
    }
}
