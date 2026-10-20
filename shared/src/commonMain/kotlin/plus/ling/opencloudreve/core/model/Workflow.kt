package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.CrInstant

/** 任务类型（`pkg/queue/task.go`；用户列表只会出现前 7 种） */
object TaskType {
    const val CREATE_ARCHIVE = "create_archive"
    const val EXTRACT_ARCHIVE = "extract_archive"
    const val RELOCATE = "relocate"
    const val REMOTE_DOWNLOAD = "remote_download"
    const val IMPORT = "import"
    const val GDRIVE_BACKUP = "gdrive_backup"
    const val GDRIVE_RESTORE = "gdrive_restore"
}

/** 任务状态（`ent/task/task.go`） */
object TaskStatus {
    const val QUEUED = "queued"
    const val PROCESSING = "processing"
    const val SUSPENDING = "suspending"
    const val ERROR = "error"
    const val CANCELED = "canceled"
    const val COMPLETED = "completed"
}

/** `GET /workflow` 的 category */
object TaskCategory {
    const val GENERAL = "general"
    const val DOWNLOADING = "downloading"
    const val DOWNLOADED = "downloaded"
}

/**
 * 工作流任务（`service/explorer/response.go:TaskResponse`）。
 * 不叫 `Task`：导出到 Swift 后会与 Swift 并发的 `Task` 重名。
 */
@Serializable
data class WorkflowTask(
    val id: String,
    val type: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val node: WorkflowNode? = null,
    val summary: TaskSummary? = null,
    val error: String? = null,
    @SerialName("error_history") val errorHistory: List<String> = emptyList(),
    /** 已执行时长（毫秒） */
    val duration: Long = 0,
    /** 暂停后恢复时间（Unix 秒） */
    @SerialName("resume_time") val resumeTime: Long = 0,
    @SerialName("retry_count") val retryCount: Int = 0,
)

/**
 * 任务摘要（`queue.Summary`）。[props] 按任务类型不同：
 * - create_archive：`src_multiple[]`、`dst`、`failed`
 * - extract_archive：`src`、`dst`
 * - relocate：`src_multiple[]`、`dst_policy_id`、`failed`、`skipped`、`relocated`
 * - import：`dst`、`src_str`、`failed`、`dst_policy_id`
 * - remote_download：`src_str`、`src`、`dst`、`failed`、`download`（见 [download]）
 */
@Serializable
data class TaskSummary(
    val phase: String? = null,
    val props: JsonObject? = null,
) {
    /** 读取字符串属性 */
    fun string(key: String): String? = (props?.get(key) as? JsonPrimitive)?.contentOrNull

    /** 读取字符串数组属性 */
    fun strings(key: String): List<String> =
        (props?.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** 读取整数属性 */
    fun int(key: String): Long? = (props?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /** 远程下载状态（仅 remote_download） */
    val download: DownloadStatus?
        get() = props?.get("download")?.let { decodeOrNull(it) }

    private fun decodeOrNull(e: JsonElement): DownloadStatus? =
        runCatching { CloudreveJson.decodeFromJsonElement(DownloadStatus.serializer(), e) }.getOrNull()
}

/** 远程下载状态（`pkg/downloader:TaskStatus`） */
@Serializable
data class DownloadStatus(
    val name: String = "",
    /** downloading / seeding / completed / error / unknown */
    val state: String = "",
    val total: Long = 0,
    val downloaded: Long = 0,
    @SerialName("download_speed") val downloadSpeed: Long = 0,
    val uploaded: Long = 0,
    @SerialName("upload_speed") val uploadSpeed: Long = 0,
    val hash: String? = null,
    val files: List<DownloadTaskFile> = emptyList(),
    /** base64 的分片位图 */
    val pieces: String? = null,
    @SerialName("num_pieces") val numPieces: Int = 0,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
data class DownloadTaskFile(
    val index: Int = 0,
    val name: String = "",
    val size: Long = 0,
    /** 0–1 */
    val progress: Double = 0.0,
    val selected: Boolean = false,
)

/** `GET /workflow` */
@Serializable
data class TaskListResponse(
    val tasks: List<WorkflowTask> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** `GET /workflow/progress/:id` 的单项；响应为 `identifier -> 进度` 的映射 */
@Serializable
data class TaskProgress(
    val total: Long = 0,
    val current: Long = 0,
    val identifier: String = "",
)

/** 节点（`service/user/response.go:Node`） */
@Serializable
data class WorkflowNode(
    val id: String,
    val name: String = "",
    /** master / slave */
    val type: String = "",
    val capabilities: BoolSet? = null,
)

/** 节点能力位（`types.NodeCapability`），也是 `GET /workflow/nodes?capability=` 的取值 */
object NodeCapability {
    const val NONE = 0
    const val CREATE_ARCHIVE = 1
    const val EXTRACT_ARCHIVE = 2
    const val REMOTE_DOWNLOAD = 3
}

/**
 * `POST /workflow/archive`（[dst] 为目标压缩包文件 URI）与 `POST /workflow/extract`（[src] 只取第一个，[dst] 为目标目录）。
 * [encoding] 为文件名编码，[password] 为压缩包密码，[fileMask] 过滤条目。
 */
@Serializable
data class ArchiveWorkflowRequest(
    val src: List<String>,
    val dst: String,
    val encoding: String? = null,
    val password: String? = null,
    @SerialName("file_mask") val fileMask: List<String>? = null,
    @SerialName("node_id") val nodeId: String? = null,
)

/** `POST /workflow/download`：[src]（URL/磁力）与 [srcFile]（种子文件 URI）二选一 */
@Serializable
data class DownloadWorkflowRequest(
    val src: List<String>? = null,
    @SerialName("src_file") val srcFile: String? = null,
    val dst: String,
    @SerialName("node_id") val nodeId: String? = null,
)

/** `PATCH /workflow/download/:id` */
@Serializable
data class SetDownloadFilesRequest(val files: List<DownloadFileSelection>)

@Serializable
data class DownloadFileSelection(
    val index: Int,
    val download: Boolean,
)

/** `POST /workflow/relocate` */
@Serializable
data class RelocateWorkflowRequest(
    val src: List<String>,
    @SerialName("dst_policy_id") val dstPolicyId: String,
)
