package plus.ling.opencloudreve.core.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.api.endpoints.WorkflowApi
import plus.ling.opencloudreve.core.model.ArchiveWorkflowRequest
import plus.ling.opencloudreve.core.model.DownloadFileSelection
import plus.ling.opencloudreve.core.model.DownloadWorkflowRequest
import plus.ling.opencloudreve.core.model.RelocateWorkflowRequest
import plus.ling.opencloudreve.core.model.TaskCategory
import plus.ling.opencloudreve.core.model.TaskListResponse
import plus.ling.opencloudreve.core.model.TaskProgress
import plus.ling.opencloudreve.core.model.WorkflowNode
import plus.ling.opencloudreve.core.model.WorkflowTask
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 单个账户的工作流任务仓库：三类列表、四种任务创建、进度、节点与下载文件选择，以及运行中任务的轮询流 */
class WorkflowRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
) {
    private fun api() = WorkflowApi(clients.client(accountId))

    /** [category] 见 `TaskCategory`；`downloading` 忽略分页一次返回全部 */
    @Throws(Exception::class)
    suspend fun list(category: String, pageSize: Int = 50, nextPageToken: String? = null): TaskListResponse =
        api().list(category, pageSize, nextPageToken)

    @Throws(Exception::class)
    suspend fun progress(id: String): Map<String, TaskProgress> = api().progress(id)

    /** 可选节点（[capability] 见 `NodeCapability`：压缩 1、解压 2、远程下载 3） */
    @Throws(Exception::class)
    suspend fun nodes(capability: Int): List<WorkflowNode> = api().nodes(capability)

    @Throws(Exception::class)
    suspend fun createArchive(request: ArchiveWorkflowRequest): WorkflowTask = api().createArchive(request)

    @Throws(Exception::class)
    suspend fun extractArchive(request: ArchiveWorkflowRequest): WorkflowTask = api().extractArchive(request)

    @Throws(Exception::class)
    suspend fun relocate(request: RelocateWorkflowRequest): WorkflowTask = api().relocate(request)

    /** 每个源一个任务；部分失败抛 40081（`aggregated` 为失败项） */
    @Throws(Exception::class)
    suspend fun createDownload(request: DownloadWorkflowRequest): List<WorkflowTask> = api().createDownload(request)

    @Throws(Exception::class)
    suspend fun selectDownloadFiles(id: String, files: List<DownloadFileSelection>) = api().selectDownloadFiles(id, files)

    @Throws(Exception::class)
    suspend fun cancelDownload(id: String) = api().cancelDownload(id)

    /**
     * 每 [interval]（默认 3 秒）拉取一次进行中的远程下载并发出完整列表。
     * 网络错误跳过本轮继续轮询；服务端业务错误终止流（由收集方处理）。收集方取消即停止。
     */
    fun pollDownloading(interval: Duration = POLL_INTERVAL): Flow<List<WorkflowTask>> = flow {
        while (true) {
            val tasks = try {
                api().list(TaskCategory.DOWNLOADING).tasks
            } catch (_: TransportException) {
                null
            }
            if (tasks != null) emit(tasks)
            delay(interval)
        }
    }

    /** 每 [interval] 拉取单个任务进度；规则同 [pollDownloading] */
    fun pollProgress(id: String, interval: Duration = POLL_INTERVAL): Flow<Map<String, TaskProgress>> = flow {
        while (true) {
            val progress = try {
                api().progress(id)
            } catch (_: TransportException) {
                null
            }
            if (progress != null) emit(progress)
            delay(interval)
        }
    }

    companion object {
        val POLL_INTERVAL: Duration = 3.seconds
    }
}
