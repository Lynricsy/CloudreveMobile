package plus.ling.opencloudreve.core.presentation.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.model.AdminFile
import plus.ling.opencloudreve.core.model.AdminGroup
import plus.ling.opencloudreve.core.model.AdminListRequest
import plus.ling.opencloudreve.core.model.AdminNode
import plus.ling.opencloudreve.core.model.AdminShare
import plus.ling.opencloudreve.core.model.AdminTask
import plus.ling.opencloudreve.core.model.AdminUser
import plus.ling.opencloudreve.core.model.QueueMetric
import plus.ling.opencloudreve.core.model.TaskCleanupRequest
import plus.ling.opencloudreve.core.presentation.handoff.AdminWebSection
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import kotlin.time.Instant

/**
 * 管理端用户列表。条件键：`user_email`/`user_nick`（包含）、`user_status`、`user_group`（组 id）；
 * 详情编辑见 [AdminUserEditViewModel]。[groups] 供“按用户组过滤”选择。
 */
class AdminUsersViewModel internal constructor(
    accountId: String,
    private val admin: AdminRepository,
    handoff: WebHandoff,
    pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
) : AdminListViewModel<AdminUser>(accountId, handoff, AdminWebSection.Users, ORDER_BY, pageSize) {
    private val mutableGroups = MutableStateFlow<List<AdminGroup>>(emptyList())

    /** 全部用户组（加载失败时为空） */
    val groups: StateFlow<List<AdminGroup>> = mutableGroups.asStateFlow()

    init {
        start()
        scope.launch { runCatching { loadAllGroups(admin) }.getOrNull()?.let { mutableGroups.value = it } }
    }

    override suspend fun fetch(request: AdminListRequest): AdminPage<AdminUser> =
        admin.users(request).let { AdminPage(it.users, it.pagination) }

    override suspend fun fetchDetail(id: Int): AdminUser = admin.user(id)
    override suspend fun deleteItems(ids: List<Int>) = admin.deleteUsers(ids)
    override fun idOf(item: AdminUser): Int = item.id

    /** 浏览该用户“我的文件”的 URI（交给 FileListViewModel） */
    fun filesUri(user: AdminUser): String = user.filesRoot().toString()

    companion object {
        val ORDER_BY = listOf("id", "nick", "email", "storage", "updated_at")
    }
}

/** 管理端分享列表。条件键：`share_user_id`、`share_file_id`、`share_id`（逗号分隔） */
class AdminSharesViewModel internal constructor(
    accountId: String,
    private val admin: AdminRepository,
    handoff: WebHandoff,
    pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
) : AdminListViewModel<AdminShare>(accountId, handoff, AdminWebSection.Shares, ORDER_BY, pageSize) {
    init {
        start()
    }

    override suspend fun fetch(request: AdminListRequest): AdminPage<AdminShare> =
        admin.shares(request).let { AdminPage(it.shares, it.pagination) }

    override suspend fun fetchDetail(id: Int): AdminShare = admin.share(id)
    override suspend fun deleteItems(ids: List<Int>) = admin.deleteShares(ids)
    override fun idOf(item: AdminShare): Int = item.id

    companion object {
        val ORDER_BY = listOf("views", "downloads", "remain_downloads")
    }
}

/**
 * 管理端文件列表（只含普通文件）。条件键：`file_name`（包含）、`file_user`（用户 id）、`file_policy`、
 * `file_metadata`、`file_shared`、`file_direct_link`（真值字符串，如 `true`）。
 * [AdminListIntent.FetchFileUrl] → [AdminListEffect.FileUrlReady]。
 */
class AdminFilesViewModel internal constructor(
    accountId: String,
    private val admin: AdminRepository,
    handoff: WebHandoff,
    pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
) : AdminListViewModel<AdminFile>(accountId, handoff, AdminWebSection.Files, ORDER_BY, pageSize) {
    init {
        start()
    }

    override suspend fun fetch(request: AdminListRequest): AdminPage<AdminFile> =
        admin.files(request).let { AdminPage(it.files, it.pagination) }

    override suspend fun fetchDetail(id: Int): AdminFile = admin.file(id)
    override suspend fun deleteItems(ids: List<Int>) = admin.deleteFiles(ids)
    override fun idOf(item: AdminFile): Int = item.id

    override fun onExtra(intent: AdminListIntent) {
        if (intent is AdminListIntent.FetchFileUrl) {
            runItemOperation(intent.id) { emit(AdminListEffect.FileUrlReady(intent.id, admin.fileUrl(intent.id))) }
        }
    }

    companion object {
        val ORDER_BY = listOf("name", "size", "created_at", "updated_at")
    }
}

/**
 * 管理端任务队列。条件键：`task_type`、`task_status`、`task_user_id`、`task_correlation_id`；不可排序。
 * [metrics] 为各队列指标（[AdminListIntent.RefreshMetrics] 刷新）；[AdminListIntent.CleanupTasks] 清理旧任务。
 */
class AdminQueueViewModel internal constructor(
    accountId: String,
    private val admin: AdminRepository,
    handoff: WebHandoff,
    pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
) : AdminListViewModel<AdminTask>(accountId, handoff, AdminWebSection.Tasks, emptyList(), pageSize) {
    private val mutableMetrics = MutableStateFlow<List<QueueMetric>>(emptyList())

    /** 队列指标（media_meta、recycle、io_intense、remote_download、thumb） */
    val metrics: StateFlow<List<QueueMetric>> = mutableMetrics.asStateFlow()

    init {
        start()
        refreshMetrics()
    }

    override suspend fun fetch(request: AdminListRequest): AdminPage<AdminTask> =
        admin.tasks(request).let { AdminPage(it.tasks, it.pagination) }

    override suspend fun fetchDetail(id: Int): AdminTask = admin.task(id)
    override suspend fun deleteItems(ids: List<Int>) = admin.deleteTasks(ids)
    override fun idOf(item: AdminTask): Int = item.id

    override fun onExtra(intent: AdminListIntent) {
        when (intent) {
            AdminListIntent.RefreshMetrics -> refreshMetrics()
            is AdminListIntent.CleanupTasks -> runItemOperation(CLEANUP_BUSY_ID) {
                admin.cleanupTasks(
                    TaskCleanupRequest(
                        notAfter = Instant.fromEpochSeconds(intent.notAfterEpochSeconds),
                        types = intent.types,
                        status = intent.statuses,
                    ),
                )
                emit(AdminListEffect.TasksCleaned)
                on(AdminListIntent.Refresh)
                refreshMetrics()
            }
            else -> Unit
        }
    }

    private fun refreshMetrics() {
        launchCatching(onError = { e -> emit(AdminListEffect.Failed(e)) }) {
            mutableMetrics.value = admin.queueMetrics()
        }
    }

    companion object {
        /** 清理进行中时 [AdminListState.busyId] 的取值 */
        const val CLEANUP_BUSY_ID = -1
    }
}

/**
 * 管理端节点列表。条件键：`node_status`（active/suspended）。
 * [AdminListIntent.TestNode] → [AdminListEffect.NodeTestResult]。“在网页后台打开”在打开详情时跳到节点详情页。
 */
class AdminNodesViewModel internal constructor(
    accountId: String,
    private val admin: AdminRepository,
    handoff: WebHandoff,
    pageSize: Int = AdminPaging.DEFAULT_PAGE_SIZE,
) : AdminListViewModel<AdminNode>(accountId, handoff, AdminWebSection.Nodes, ORDER_BY, pageSize) {
    init {
        start()
    }

    override suspend fun fetch(request: AdminListRequest): AdminPage<AdminNode> =
        admin.nodes(request).let { AdminPage(it.nodes, it.pagination) }

    override suspend fun fetchDetail(id: Int): AdminNode = admin.node(id)

    /** 节点不在移动端删除（网页后台管理） */
    override val supportsDelete: Boolean get() = false

    override suspend fun deleteItems(ids: List<Int>) = throw UnsupportedOperationException("Deleting nodes is not supported")
    override fun idOf(item: AdminNode): Int = item.id

    override fun onExtra(intent: AdminListIntent) {
        if (intent is AdminListIntent.TestNode) {
            runItemOperation(intent.id) {
                val result = try {
                    admin.testNode(intent.id)
                    AdminListEffect.NodeTestResult(intent.id, success = true, message = null)
                } catch (e: ApiException) {
                    AdminListEffect.NodeTestResult(intent.id, success = false, message = e.serverMsg)
                }
                emit(result)
            }
        }
    }

    companion object {
        val ORDER_BY = listOf("name", "weight", "updated_at")
    }
}

/** 拉取全部用户组（数量很少，按 100 一页取完） */
internal suspend fun loadAllGroups(admin: AdminRepository): List<AdminGroup> {
    val all = ArrayList<AdminGroup>()
    var page = 1
    while (true) {
        val res = admin.groups(AdminListRequest(page = page, pageSize = 100, orderBy = "id", orderDirection = "asc"))
        all += res.groups
        if (!AdminPaging.hasMore(res.pagination, 100) || res.groups.isEmpty()) return all
        page = AdminPaging.nextRequestPage(AdminPaging.loadedPages(res.pagination))
    }
}
