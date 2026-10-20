package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.model.ArchiveWorkflowRequest
import plus.ling.opencloudreve.core.model.DownloadFileSelection
import plus.ling.opencloudreve.core.model.DownloadWorkflowRequest
import plus.ling.opencloudreve.core.model.RelocateWorkflowRequest
import plus.ling.opencloudreve.core.model.SetDownloadFilesRequest
import plus.ling.opencloudreve.core.model.TaskListResponse
import plus.ling.opencloudreve.core.model.TaskProgress
import plus.ling.opencloudreve.core.model.WorkflowNode
import plus.ling.opencloudreve.core.model.WorkflowTask

/** 后台任务接口（`/workflow/…`） */
class WorkflowApi(private val client: ApiClient) {

    /**
     * `GET /workflow`：[category] 见 `TaskCategory`；[pageSize] 10–100。
     * `downloading` 分类忽略分页，一次返回全部进行中的下载。
     */
    @Throws(Exception::class)
    suspend fun list(category: String, pageSize: Int = 50, nextPageToken: String? = null): TaskListResponse =
        client.get(
            "/workflow",
            mapOf("category" to category, "page_size" to pageSize, "next_page_token" to nextPageToken),
        )

    /** `GET /workflow/progress/:id`：identifier → 进度；任务不在内存（如已结束）时为空 */
    @Throws(Exception::class)
    suspend fun progress(id: String): Map<String, TaskProgress> =
        client.call(
            HttpMethod.Get,
            "/workflow/progress/$id",
            MapSerializer(String.serializer(), TaskProgress.serializer()),
        )

    /** `GET /workflow/nodes?capability=`：可选节点（[capability] 见 `NodeCapability`） */
    @Throws(Exception::class)
    suspend fun nodes(capability: Int): List<WorkflowNode> =
        client.call(
            HttpMethod.Get,
            "/workflow/nodes",
            ListSerializer(WorkflowNode.serializer()),
            mapOf("capability" to capability),
        )

    /** `POST /workflow/archive`：压缩（dst 为目标压缩包文件 URI） */
    @Throws(Exception::class)
    suspend fun createArchive(request: ArchiveWorkflowRequest): WorkflowTask = client.post("/workflow/archive", request)

    /** `POST /workflow/extract`：解压（src 取第一个，dst 为目标目录） */
    @Throws(Exception::class)
    suspend fun extractArchive(request: ArchiveWorkflowRequest): WorkflowTask = client.post("/workflow/extract", request)

    /** `POST /workflow/relocate`：转移存储策略 */
    @Throws(Exception::class)
    suspend fun relocate(request: RelocateWorkflowRequest): WorkflowTask = client.post("/workflow/relocate", request)

    /** `POST /workflow/download`：创建远程下载，每个源一个任务；部分失败 40081 */
    @Throws(Exception::class)
    suspend fun createDownload(request: DownloadWorkflowRequest): List<WorkflowTask> =
        client.call(
            HttpMethod.Post,
            "/workflow/download",
            ListSerializer(WorkflowTask.serializer()),
            body = client.encodeBody(request),
        )

    /** `PATCH /workflow/download/:id`：选择要下载的文件（仅监控阶段） */
    @Throws(Exception::class)
    suspend fun selectDownloadFiles(id: String, files: List<DownloadFileSelection>) {
        client.patch<Unit, SetDownloadFilesRequest>("/workflow/download/$id", SetDownloadFilesRequest(files))
    }

    /** `DELETE /workflow/download/:id`：取消远程下载 */
    @Throws(Exception::class)
    suspend fun cancelDownload(id: String) {
        client.deleteNoBody<Unit>("/workflow/download/$id")
    }
}
