package plus.ling.opencloudreve.core.presentation.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.model.DownloadFileSelection
import plus.ling.opencloudreve.core.model.DownloadStatus
import plus.ling.opencloudreve.core.model.TaskCategory
import plus.ling.opencloudreve.core.model.TaskProgress
import plus.ling.opencloudreve.core.model.TaskStatus
import plus.ling.opencloudreve.core.model.TaskType
import plus.ling.opencloudreve.core.model.WorkflowTask
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.workflow.WorkflowRepository
import kotlin.time.Duration

/** 任务列表中的一行（含派生字段） */
data class WorkflowTaskRow(val task: WorkflowTask) {
    val id: String get() = task.id
    val type: String get() = task.type
    val status: String get() = task.status
    val phase: String? get() = task.summary?.phase?.takeIf { it.isNotEmpty() }

    /** 排队/处理中/挂起等待 */
    val isActive: Boolean get() = status == TaskStatus.QUEUED || status == TaskStatus.PROCESSING || status == TaskStatus.SUSPENDING
    val isRemoteDownload: Boolean get() = type == TaskType.REMOTE_DOWNLOAD

    /** 远程下载的下载器状态 */
    val download: DownloadStatus? get() = if (isRemoteDownload) task.summary?.download else null

    /** 下载进度 0–1（总大小未知为 null） */
    val downloadProgress: Double?
        get() = download?.takeIf { it.total > 0 }?.let { (it.downloaded.toDouble() / it.total).coerceIn(0.0, 1.0) }

    /** 源：`src_multiple`（压缩/转移）、`src`（解压/种子文件）或 `src_str`（远程下载 URL） */
    val sources: List<String>
        get() {
            val s = task.summary ?: return emptyList()
            val multiple = s.strings("src_multiple")
            if (multiple.isNotEmpty()) return multiple
            return listOfNotNull(s.string("src")?.takeIf { it.isNotEmpty() } ?: s.string("src_str")?.takeIf { it.isNotEmpty() })
        }

    /** 目标（目录或压缩包 URI；转移任务为空，见 [dstPolicyId]） */
    val destination: String? get() = task.summary?.string("dst")?.takeIf { it.isNotEmpty() }
    val dstPolicyId: String? get() = task.summary?.string("dst_policy_id")?.takeIf { it.isNotEmpty() }
    val failedCount: Long get() = task.summary?.int("failed") ?: 0

    /** 可取消：进行中的远程下载 */
    val canCancel: Boolean get() = isRemoteDownload && isActive

    /** 可选择要下载的文件：处理中/挂起且处于 monitor 阶段、下载器已列出文件 */
    val canSelectFiles: Boolean
        get() = isRemoteDownload &&
            (status == TaskStatus.PROCESSING || status == TaskStatus.SUSPENDING) &&
            phase == PHASE_MONITOR &&
            download?.files?.isNotEmpty() == true

    companion object {
        const val PHASE_MONITOR = "monitor"
    }
}

/** 任务详情 */
data class WorkflowDetail(
    val row: WorkflowTaskRow,
    /** `GET /workflow/progress/:id`：identifier → 进度；任务不在内存（已结束）时为空 */
    val progress: Map<String, TaskProgress> = emptyMap(),
    /** 正在轮询进度 */
    val polling: Boolean = false,
)

data class WorkflowListState(
    val category: String = TaskCategory.GENERAL,
    val rows: List<WorkflowTaskRow> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 加载失败且无内容时的整页错误 */
    val error: UiError? = null,
    /** “下载中”分类正在 3 秒轮询 */
    val polling: Boolean = false,
    val foreground: Boolean = true,
    val detail: WorkflowDetail? = null,
    /** 正在执行操作（取消/选择文件）的任务 id */
    val busyIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = rows.isEmpty() && !loading && error == null

    companion object {
        val CATEGORIES: List<String> = listOf(TaskCategory.GENERAL, TaskCategory.DOWNLOADING, TaskCategory.DOWNLOADED)
        const val PAGE_SIZE = 50
    }
}

sealed interface WorkflowListIntent {
    /** `general` / `downloading` / `downloaded` */
    data class SetCategory(val category: String) : WorkflowListIntent
    data object Refresh : WorkflowListIntent
    data object LoadMore : WorkflowListIntent

    /** 页面前后台：后台停止所有轮询，回到前台重新拉取 */
    data class SetForeground(val foreground: Boolean) : WorkflowListIntent
    data class OpenDetail(val id: String) : WorkflowListIntent
    data object CloseDetail : WorkflowListIntent

    /** 取消远程下载 */
    data class Cancel(val id: String) : WorkflowListIntent

    /** 选择远程下载的文件（`PATCH /workflow/download/:id`） */
    data class SelectFiles(val id: String, val files: List<DownloadFileSelection>) : WorkflowListIntent
}

sealed interface WorkflowListEffect {
    data class Canceled(val id: String) : WorkflowListEffect
    data class FilesSelected(val id: String) : WorkflowListEffect
    data class Failed(val error: UiError) : WorkflowListEffect
}

/**
 * 工作流任务（任务 Tab 第二段）：三类列表；“下载中”在前台且列表非空时每 [pollInterval] 轮询，
 * 列表变空/切换分类/进入后台/业务错误时停止；详情页对进行中任务轮询 `progress/:id`，结束后刷新状态并停止。
 */
class WorkflowListViewModel(
    val accountId: String,
    initialCategory: String,
    private val workflows: WorkflowRepository,
    private val pollInterval: Duration = WorkflowRepository.POLL_INTERVAL,
) : CoreViewModel<WorkflowListState, WorkflowListIntent, WorkflowListEffect>(
    WorkflowListState(category = initialCategory),
) {
    private var nextToken: String? = null
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    init {
        load(refreshing = false)
    }

    override fun on(intent: WorkflowListIntent) {
        when (intent) {
            is WorkflowListIntent.SetCategory -> {
                if (intent.category == currentState.category) return
                updateState { it.copy(category = intent.category, rows = emptyList(), hasMore = false, error = null) }
                load(refreshing = false)
            }
            WorkflowListIntent.Refresh -> load(refreshing = true)
            WorkflowListIntent.LoadMore -> loadMore()
            is WorkflowListIntent.SetForeground -> setForeground(intent.foreground)
            is WorkflowListIntent.OpenDetail -> openDetail(intent.id)
            WorkflowListIntent.CloseDetail -> {
                detailJob?.cancel()
                updateState { it.copy(detail = null) }
            }
            is WorkflowListIntent.Cancel -> act(intent.id, WorkflowListEffect.Canceled(intent.id)) { workflows.cancelDownload(it) }
            is WorkflowListIntent.SelectFiles -> act(intent.id, WorkflowListEffect.FilesSelected(intent.id)) {
                workflows.selectDownloadFiles(it, intent.files)
            }
        }
    }

    private fun setForeground(foreground: Boolean) {
        if (foreground == currentState.foreground) return
        updateState { it.copy(foreground = foreground) }
        if (foreground) {
            load(refreshing = true)
            currentState.detail?.let { if (it.row.isActive) pollDetail(it.row.id) }
        } else {
            loadJob?.cancel()
            detailJob?.cancel()
            updateState { s -> s.copy(polling = false, refreshing = false, detail = s.detail?.copy(polling = false)) }
        }
    }

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        val category = currentState.category
        updateState { it.copy(loading = it.rows.isEmpty(), refreshing = refreshing && it.rows.isNotEmpty(), error = null) }
        loadJob = if (category == TaskCategory.DOWNLOADING) startDownloadingPoll() else scope.launch {
            try {
                val page = workflows.list(category, WorkflowListState.PAGE_SIZE)
                nextToken = page.pagination.nextToken
                applyRows(page.tasks, hasMore = page.pagination.hasMore)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** “下载中”：首次拉取即第一次轮询；列表为空即停止 */
    private fun startDownloadingPoll(): Job = scope.launch {
        if (!currentState.foreground) return@launch
        updateState { it.copy(polling = true) }
        try {
            workflows.pollDownloading(pollInterval)
                .transformWhile { tasks ->
                    emit(tasks)
                    tasks.isNotEmpty() && currentState.foreground
                }
                .collect { tasks -> applyRows(tasks, hasMore = false) }
            updateState { it.copy(polling = false) }
        } catch (e: CancellationException) {
            updateState { it.copy(polling = false) }
            throw e
        } catch (e: Exception) {
            updateState { it.copy(polling = false) }
            fail(e)
        }
    }

    private fun applyRows(tasks: List<WorkflowTask>, hasMore: Boolean) {
        val rows = tasks.map(::WorkflowTaskRow)
        updateState { s ->
            s.copy(
                rows = rows,
                loading = false,
                refreshing = false,
                hasMore = hasMore,
                error = null,
                detail = s.detail?.let { d -> rows.firstOrNull { it.id == d.row.id }?.let { d.copy(row = it) } ?: d },
            )
        }
    }

    private fun fail(e: Exception) {
        val err = e.toUiError()
        updateState { it.copy(loading = false, refreshing = false, error = if (it.rows.isEmpty()) err else null) }
        if (currentState.rows.isNotEmpty()) sendEffect(WorkflowListEffect.Failed(err))
    }

    private fun loadMore() {
        val s = currentState
        val token = nextToken
        if (!s.hasMore || s.loadingMore || s.loading || token == null || s.category == TaskCategory.DOWNLOADING) return
        loadJob = scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                val page = workflows.list(s.category, WorkflowListState.PAGE_SIZE, token)
                nextToken = page.pagination.nextToken
                updateState { st ->
                    val known = st.rows.mapTo(HashSet()) { it.id }
                    st.copy(
                        rows = st.rows + page.tasks.filter { it.id !in known }.map(::WorkflowTaskRow),
                        loadingMore = false,
                        hasMore = page.pagination.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false) }
                sendEffect(WorkflowListEffect.Failed(e.toUiError()))
            }
        }
    }

    private fun openDetail(id: String) {
        val row = currentState.rows.firstOrNull { it.id == id } ?: return
        detailJob?.cancel()
        updateState { it.copy(detail = WorkflowDetail(row)) }
        pollDetail(id)
    }

    /** 详情进度：进行中每 [pollInterval] 拉一次；进度为空时刷新任务状态，任务结束即停止 */
    private fun pollDetail(id: String) {
        detailJob?.cancel()
        detailJob = scope.launch {
            updateState { s -> s.copy(detail = s.detail?.copy(polling = true)) }
            try {
                while (isActive) {
                    val progress = try {
                        workflows.progress(id)
                    } catch (_: TransportException) {
                        null
                    }
                    if (progress != null) updateState { s -> s.copy(detail = s.detail?.copy(progress = progress)) }
                    if (progress.isNullOrEmpty()) refreshTask(id)
                    val row = currentState.detail?.row ?: break
                    if (!row.isActive) break
                    delay(pollInterval)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(WorkflowListEffect.Failed(e.toUiError()))
            }
            updateState { s -> s.copy(detail = s.detail?.copy(polling = false)) }
        }
    }

    /** 重新拉取任务所在分类的第一页更新该任务；远程下载离开“下载中”后到“已完成”里找 */
    private suspend fun refreshTask(id: String) {
        val row = currentState.detail?.row ?: return
        val primary = categoryOf(row)
        val found = workflows.list(primary, WorkflowListState.PAGE_SIZE).tasks.firstOrNull { it.id == id }
            ?: if (primary == TaskCategory.DOWNLOADING) {
                workflows.list(TaskCategory.DOWNLOADED, WorkflowListState.PAGE_SIZE).tasks.firstOrNull { it.id == id }
            } else {
                null
            }
        if (found == null) return
        val updated = WorkflowTaskRow(found)
        updateState { s ->
            s.copy(
                detail = s.detail?.copy(row = updated),
                rows = s.rows.map { if (it.id == id) updated else it },
            )
        }
    }

    private fun categoryOf(row: WorkflowTaskRow): String = when {
        !row.isRemoteDownload -> TaskCategory.GENERAL
        row.isActive -> TaskCategory.DOWNLOADING
        else -> TaskCategory.DOWNLOADED
    }

    private fun act(id: String, success: WorkflowListEffect, block: suspend (String) -> Unit) {
        if (id in currentState.busyIds) return
        updateState { it.copy(busyIds = it.busyIds + id) }
        scope.launch {
            try {
                block(id)
                sendEffect(success)
                load(refreshing = true)
                currentState.detail?.takeIf { it.row.id == id }?.let { pollDetail(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(WorkflowListEffect.Failed(e.toUiError()))
            } finally {
                updateState { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }
}
