package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import plus.ling.opencloudreve.core.api.CrInstant

/*
 * 管理端模型。管理端直接输出 ent 实体：id 为整数（不是 hashid），关联对象在 `edges` 下，
 * 另附 `hash_id`/`user_hash_id` 等 hashid 扩展字段（`service/admin/response.go`）。
 * 敏感字段（节点 slave_key、存储策略密钥等）不建模，解码时直接丢弃。
 */

/**
 * 通用列表请求（`service/admin/list.go:AdminListService`）。
 * [page] 从 1 起；响应 `pagination.page` 从 0 起。[orderDirection] 只有 `desc` 为降序。
 */
@Serializable
data class AdminListRequest(
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    @SerialName("order_by") val orderBy: String = "",
    @SerialName("order_direction") val orderDirection: String = OrderDirection.DESC,
    val conditions: Map<String, String> = emptyMap(),
)

/** 各列表的 conditions 键 */
object AdminConditions {
    const val USER_STATUS = "user_status"
    const val USER_GROUP = "user_group"
    const val USER_NICK = "user_nick"
    const val USER_EMAIL = "user_email"
    const val SHARE_USER_ID = "share_user_id"
    const val SHARE_FILE_ID = "share_file_id"
    const val SHARE_ID = "share_id"
    const val FILE_NAME = "file_name"
    const val FILE_USER = "file_user"
    const val FILE_POLICY = "file_policy"
    const val FILE_METADATA = "file_metadata"
    const val FILE_SHARED = "file_shared"
    const val FILE_DIRECT_LINK = "file_direct_link"
    const val TASK_TYPE = "task_type"
    const val TASK_STATUS = "task_status"
    const val TASK_USER_ID = "task_user_id"
    const val TASK_CORRELATION_ID = "task_correlation_id"
    const val NODE_STATUS = "node_status"
}

/** `GET /admin/summary` */
@Serializable
data class AdminSummary(
    /** 缓存未命中且 generate=false 时为 null */
    @SerialName("metrics_summary") val metricsSummary: MetricsSummary? = null,
    @SerialName("site_urls") val siteUrls: List<String> = emptyList(),
    val version: AdminVersion? = null,
)

/** 近 12 天每日新增统计 */
@Serializable
data class MetricsSummary(
    val dates: List<CrInstant> = emptyList(),
    val files: List<Int> = emptyList(),
    val users: List<Int> = emptyList(),
    val shares: List<Int> = emptyList(),
    @SerialName("file_total") val fileTotal: Int = 0,
    @SerialName("user_total") val userTotal: Int = 0,
    @SerialName("share_total") val shareTotal: Int = 0,
    @SerialName("entities_total") val entitiesTotal: Int = 0,
    @SerialName("generated_at") val generatedAt: CrInstant? = null,
)

@Serializable
data class AdminVersion(
    val version: String = "",
    val commit: String = "",
)

/** 用户组（`ent.Group`） */
@Serializable
data class AdminGroup(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val name: String = "",
    @SerialName("max_storage") val maxStorage: Long = 0,
    @SerialName("speed_limit") val speedLimit: Int = 0,
    val permissions: BoolSet? = null,
    /** `types.GroupSetting` 原样保留 */
    val settings: JsonObject? = null,
    @SerialName("storage_policy_id") val storagePolicyId: Int = 0,
)

@Serializable
data class AdminGroupListResponse(
    val groups: List<AdminGroup> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** 用户（`GetUserResponse` = `ent.User` + 扩展） */
@Serializable
data class AdminUser(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val email: String = "",
    val nick: String = "",
    /** active / inactive / manual_banned / sys_banned */
    val status: String = "",
    /** 已用空间（字节） */
    val storage: Long = 0,
    val avatar: String = "",
    /** `types.UserSetting` 原样保留 */
    val settings: JsonObject? = null,
    /** 用户组 id */
    @SerialName("group_users") val groupUsers: Int = 0,
    val edges: AdminUserEdges = AdminUserEdges(),
    /** 用户 hashid，用于 `cloudreve://<hash_id>@my` */
    @SerialName("hash_id") val hashId: String = "",
    @SerialName("two_fa_enabled") val twoFaEnabled: Boolean = false,
    /** 仅详情接口 */
    val capacity: Capacity? = null,
) {
    /** 浏览该用户“我的文件”的根 URI */
    fun filesRoot(): CrUri = CrUri.my(hashId)
}

@Serializable
data class AdminUserEdges(
    val group: AdminGroup? = null,
    val passkey: List<JsonObject> = emptyList(),
)

@Serializable
data class AdminUserListResponse(
    val users: List<AdminUser> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/**
 * `PUT /admin/user/:id`。服务端只写入 [user] 的 email/nick/avatar/status/group_users（其余忽略），
 * 因此必须带回原 avatar。[password] 非空则重置密码（6–128）；[twoFa] 非空则清除 2FA（不需要时保持 null）。
 */
@Serializable
data class AdminUserUpsertRequest(
    val user: AdminUserPatch,
    val password: String? = null,
    @SerialName("two_fa") val twoFa: String? = null,
) {
    companion object {
        /** 清除 2FA 时传给 [twoFa] 的值 */
        const val CLEAR_TWO_FA = "clear"
    }
}

@Serializable
data class AdminUserPatch(
    val id: Int,
    val email: String,
    val nick: String,
    val avatar: String,
    val status: String,
    @SerialName("group_users") val groupUsers: Int,
) {
    companion object {
        /** 以现有用户为基础构造（保留 avatar） */
        fun from(user: AdminUser): AdminUserPatch =
            AdminUserPatch(user.id, user.email, user.nick, user.avatar, user.status, user.groupUsers)
    }
}

/** 文件（`GetFileResponse` = `ent.File` + 扩展）；列表只含普通文件，不含目录与软链接 */
@Serializable
data class AdminFile(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val type: Int = FileType.FILE,
    val name: String = "",
    @SerialName("owner_id") val ownerId: Int = 0,
    val size: Long = 0,
    @SerialName("primary_entity") val primaryEntity: Int = 0,
    @SerialName("is_symbolic") val isSymbolic: Boolean = false,
    @SerialName("storage_policy_files") val storagePolicyFiles: Int = 0,
    val edges: AdminFileEdges = AdminFileEdges(),
    @SerialName("user_hash_id") val userHashId: String? = null,
    @SerialName("file_hash_id") val fileHashId: String? = null,
    /** 仅详情接口：直链 id（字符串化的整数）→ URL */
    @SerialName("direct_link_map") val directLinkMap: Map<String, String> = emptyMap(),
)

@Serializable
data class AdminFileEdges(
    val owner: AdminUser? = null,
    @SerialName("storage_policies") val storagePolicies: AdminStoragePolicy? = null,
    val metadata: List<AdminMetadata> = emptyList(),
    val entities: List<AdminEntity> = emptyList(),
    val shares: List<AdminShare> = emptyList(),
    @SerialName("direct_links") val directLinks: List<AdminDirectLink> = emptyList(),
)

@Serializable
data class AdminFileListResponse(
    val files: List<AdminFile> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** 存储策略（`ent.StoragePolicy`，只保留非敏感字段） */
@Serializable
data class AdminStoragePolicy(
    val id: Int,
    val name: String = "",
    val type: String = "",
    @SerialName("max_size") val maxSize: Long = 0,
    @SerialName("node_id") val nodeId: Int = 0,
)

@Serializable
data class AdminMetadata(
    val id: Int = 0,
    val name: String = "",
    val value: String = "",
    @SerialName("is_public") val isPublic: Boolean = false,
)

/** 实体（`ent.Entity`） */
@Serializable
data class AdminEntity(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    val type: Int = EntityType.VERSION,
    val source: String = "",
    val size: Long = 0,
    @SerialName("reference_count") val referenceCount: Int = 0,
    @SerialName("storage_policy_entities") val storagePolicyEntities: Int = 0,
    @SerialName("created_by") val createdBy: Int = 0,
    @SerialName("upload_session_id") val uploadSessionId: String? = null,
    val sha256: String? = null,
    val edges: AdminEntityEdges = AdminEntityEdges(),
)

@Serializable
data class AdminEntityEdges(
    val user: AdminUser? = null,
    @SerialName("storage_policy") val storagePolicy: AdminStoragePolicy? = null,
)

@Serializable
data class AdminDirectLink(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    val name: String = "",
    val downloads: Int = 0,
    @SerialName("file_id") val fileId: Int = 0,
    val speed: Int = 0,
)

/** 分享（`GetShareResponse` = `ent.Share` + 扩展）；[password] 为明文 */
@Serializable
data class AdminShare(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val password: String? = null,
    val views: Int = 0,
    val downloads: Int = 0,
    val expires: CrInstant? = null,
    @SerialName("remain_downloads") val remainDownloads: Int? = null,
    val props: AdminShareProps? = null,
    val edges: AdminShareEdges = AdminShareEdges(),
    @SerialName("user_hash_id") val userHashId: String? = null,
    @SerialName("share_link") val shareLink: String? = null,
)

@Serializable
data class AdminShareProps(
    @SerialName("share_view") val shareView: Boolean = false,
    @SerialName("show_read_me") val showReadMe: Boolean = false,
)

@Serializable
data class AdminShareEdges(
    val user: AdminUser? = null,
    val file: AdminFile? = null,
)

@Serializable
data class AdminShareListResponse(
    val shares: List<AdminShare> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** 任务（`GetTaskResponse` = `ent.Task` + 扩展） */
@Serializable
data class AdminTask(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    val type: String = "",
    val status: String = "",
    @SerialName("public_state") val publicState: AdminTaskPublicState? = null,
    /** 内部状态 JSON 字符串 */
    @SerialName("private_state") val privateState: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
    @SerialName("user_tasks") val userTasks: Int = 0,
    val edges: AdminTaskEdges = AdminTaskEdges(),
    @SerialName("user_hash_id") val userHashId: String? = null,
    @SerialName("task_hash_id") val taskHashId: String? = null,
    val summary: TaskSummary? = null,
    val node: AdminNode? = null,
)

@Serializable
data class AdminTaskPublicState(
    val error: String? = null,
    @SerialName("error_history") val errorHistory: List<String> = emptyList(),
    /** 纳秒 */
    @SerialName("executed_duration") val executedDuration: Long = 0,
    @SerialName("retry_count") val retryCount: Int = 0,
    @SerialName("resume_time") val resumeTime: Long = 0,
    /** 从机任务属性（后端键名 `master_site_u_rl` 原样保留在 JSON 中） */
    @SerialName("slave_task_props") val slaveTaskProps: JsonObject? = null,
)

@Serializable
data class AdminTaskEdges(val user: AdminUser? = null)

@Serializable
data class AdminTaskListResponse(
    val tasks: List<AdminTask> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** `POST /admin/queue/cleanup`；[status] 为空时服务端默认三种终态 */
@Serializable
data class TaskCleanupRequest(
    @SerialName("not_after") val notAfter: CrInstant,
    val types: List<String> = emptyList(),
    val status: List<String> = emptyList(),
)

/** `GET /admin/queue/metrics` 单项；name 依次为 media_meta、recycle、io_intense、remote_download、thumb */
@Serializable
data class QueueMetric(
    val name: String = "",
    @SerialName("busy_workers") val busyWorkers: Int = 0,
    @SerialName("success_tasks") val successTasks: Int = 0,
    @SerialName("failure_tasks") val failureTasks: Int = 0,
    @SerialName("submitted_tasks") val submittedTasks: Int = 0,
    @SerialName("suspending_tasks") val suspendingTasks: Int = 0,
)

/**
 * 节点（`ent.Node`）。`slave_key`、下载器密码/令牌不建模（解码即丢弃）；
 * [settings] 原样保留（其中仍可能含下载器凭据，展示时需脱敏）。
 */
@Serializable
data class AdminNode(
    val id: Int,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    /** active / suspended */
    val status: String = "",
    val name: String = "",
    /** master / slave */
    val type: String = "",
    val server: String = "",
    val capabilities: BoolSet? = null,
    val settings: JsonObject? = null,
    val weight: Int = 0,
    val edges: AdminNodeEdges = AdminNodeEdges(),
)

@Serializable
data class AdminNodeEdges(
    @SerialName("storage_policy") val storagePolicy: List<AdminStoragePolicy> = emptyList(),
)

@Serializable
data class AdminNodeListResponse(
    val nodes: List<AdminNode> = emptyList(),
    val pagination: Pagination = Pagination(),
)
