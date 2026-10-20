package plus.ling.opencloudreve.core.admin

import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.endpoints.AdminApi
import plus.ling.opencloudreve.core.model.AdminFile
import plus.ling.opencloudreve.core.model.AdminFileListResponse
import plus.ling.opencloudreve.core.model.AdminGroupListResponse
import plus.ling.opencloudreve.core.model.AdminListRequest
import plus.ling.opencloudreve.core.model.AdminNode
import plus.ling.opencloudreve.core.model.AdminNodeListResponse
import plus.ling.opencloudreve.core.model.AdminShare
import plus.ling.opencloudreve.core.model.AdminShareListResponse
import plus.ling.opencloudreve.core.model.AdminSummary
import plus.ling.opencloudreve.core.model.AdminTask
import plus.ling.opencloudreve.core.model.AdminTaskListResponse
import plus.ling.opencloudreve.core.model.AdminUser
import plus.ling.opencloudreve.core.model.AdminUserListResponse
import plus.ling.opencloudreve.core.model.AdminUserUpsertRequest
import plus.ling.opencloudreve.core.model.QueueMetric
import plus.ling.opencloudreve.core.model.TaskCleanupRequest

/**
 * 单个账户的轻量管理仓库（仅管理员；入口由 `User.isAdmin` 门控）。
 * 列表请求 `page` 从 1 起、响应从 0 起（见 `AdminListRequest`）。
 */
class AdminRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
) {
    private fun api() = AdminApi(clients.client(accountId))

    @Throws(Exception::class)
    suspend fun summary(generate: Boolean = true): AdminSummary = api().summary(generate)

    @Throws(Exception::class)
    suspend fun users(request: AdminListRequest): AdminUserListResponse = api().users(request)

    @Throws(Exception::class)
    suspend fun user(id: Int): AdminUser = api().user(id)

    @Throws(Exception::class)
    suspend fun updateUser(request: AdminUserUpsertRequest): AdminUser = api().updateUser(request)

    @Throws(Exception::class)
    suspend fun calibrateUser(id: Int): AdminUser = api().calibrateUser(id)

    @Throws(Exception::class)
    suspend fun deleteUsers(ids: List<Int>) = api().deleteUsers(ids)

    @Throws(Exception::class)
    suspend fun groups(request: AdminListRequest): AdminGroupListResponse = api().groups(request)

    @Throws(Exception::class)
    suspend fun shares(request: AdminListRequest): AdminShareListResponse = api().shares(request)

    @Throws(Exception::class)
    suspend fun share(id: Int): AdminShare = api().share(id)

    @Throws(Exception::class)
    suspend fun deleteShares(ids: List<Int>) = api().deleteShares(ids)

    @Throws(Exception::class)
    suspend fun files(request: AdminListRequest): AdminFileListResponse = api().files(request)

    @Throws(Exception::class)
    suspend fun file(id: Int): AdminFile = api().file(id)

    @Throws(Exception::class)
    suspend fun fileUrl(id: Int): String = api().fileUrl(id)

    @Throws(Exception::class)
    suspend fun deleteFiles(ids: List<Int>) = api().deleteFiles(ids)

    @Throws(Exception::class)
    suspend fun tasks(request: AdminListRequest): AdminTaskListResponse = api().tasks(request)

    @Throws(Exception::class)
    suspend fun task(id: Int): AdminTask = api().task(id)

    @Throws(Exception::class)
    suspend fun deleteTasks(ids: List<Int>) = api().deleteTasks(ids)

    @Throws(Exception::class)
    suspend fun cleanupTasks(request: TaskCleanupRequest) = api().cleanupTasks(request)

    @Throws(Exception::class)
    suspend fun queueMetrics(): List<QueueMetric> = api().queueMetrics()

    @Throws(Exception::class)
    suspend fun nodes(request: AdminListRequest): AdminNodeListResponse = api().nodes(request)

    @Throws(Exception::class)
    suspend fun node(id: Int): AdminNode = api().node(id)

    @Throws(Exception::class)
    suspend fun testNode(id: Int) = api().testNode(id)
}
