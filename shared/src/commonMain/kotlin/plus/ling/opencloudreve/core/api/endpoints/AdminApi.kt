package plus.ling.opencloudreve.core.api.endpoints

import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import plus.ling.opencloudreve.core.api.ApiClient
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
import plus.ling.opencloudreve.core.model.IdsRequest
import plus.ling.opencloudreve.core.model.QueueMetric
import plus.ling.opencloudreve.core.model.TaskCleanupRequest

/**
 * 轻量管理接口（`/admin/…`，需用户组 is_admin）。列表统一 `POST` [AdminListRequest]；
 * 实体 id 为整数。打开他人文件不走这里，而是用 [FileApi] 浏览 `cloudreve://<hash_id>@my`。
 */
class AdminApi(private val client: ApiClient) {

    /** `GET /admin/summary?generate=`：[generate] 为 true 时缓存未命中会现场统计图表数据 */
    @Throws(Exception::class)
    suspend fun summary(generate: Boolean = true): AdminSummary =
        client.get("/admin/summary", mapOf("generate" to generate))

    // ---- 用户 ----

    @Throws(Exception::class)
    suspend fun users(request: AdminListRequest): AdminUserListResponse = client.post("/admin/user", request)

    /** 详情（含 capacity） */
    @Throws(Exception::class)
    suspend fun user(id: Int): AdminUser = client.get("/admin/user/$id")

    /** `PUT /admin/user/:id`：只写入 email/nick/avatar/status/group_users，返回最新用户 */
    @Throws(Exception::class)
    suspend fun updateUser(request: AdminUserUpsertRequest): AdminUser =
        client.put("/admin/user/${request.user.id}", request)

    /** `POST /admin/user/:id/calibrate`：重新计算已用空间 */
    @Throws(Exception::class)
    suspend fun calibrateUser(id: Int): AdminUser =
        client.call(HttpMethod.Post, "/admin/user/$id/calibrate", AdminUser.serializer())

    /** `POST /admin/user/batch/delete`：删除用户及其全部文件（不可恢复） */
    @Throws(Exception::class)
    suspend fun deleteUsers(ids: List<Int>) {
        client.post<Unit, IdsRequest>("/admin/user/batch/delete", IdsRequest(ids))
    }

    /** `POST /admin/group`：用户组列表（编辑用户时选择用户组） */
    @Throws(Exception::class)
    suspend fun groups(request: AdminListRequest): AdminGroupListResponse = client.post("/admin/group", request)

    // ---- 分享 ----

    @Throws(Exception::class)
    suspend fun shares(request: AdminListRequest): AdminShareListResponse = client.post("/admin/share", request)

    @Throws(Exception::class)
    suspend fun share(id: Int): AdminShare = client.get("/admin/share/$id")

    @Throws(Exception::class)
    suspend fun deleteShares(ids: List<Int>) {
        client.post<Unit, IdsRequest>("/admin/share/batch/delete", IdsRequest(ids))
    }

    // ---- 文件 ----

    @Throws(Exception::class)
    suspend fun files(request: AdminListRequest): AdminFileListResponse = client.post("/admin/file", request)

    @Throws(Exception::class)
    suspend fun file(id: Int): AdminFile = client.get("/admin/file/$id")

    /** `GET /admin/file/url/:id`：主版本下载地址（1 小时有效） */
    @Throws(Exception::class)
    suspend fun fileUrl(id: Int): String = client.get("/admin/file/url/$id")

    @Throws(Exception::class)
    suspend fun deleteFiles(ids: List<Int>) {
        client.post<Unit, IdsRequest>("/admin/file/batch/delete", IdsRequest(ids))
    }

    // ---- 任务队列 ----

    @Throws(Exception::class)
    suspend fun tasks(request: AdminListRequest): AdminTaskListResponse = client.post("/admin/queue", request)

    @Throws(Exception::class)
    suspend fun task(id: Int): AdminTask = client.get("/admin/queue/$id")

    @Throws(Exception::class)
    suspend fun deleteTasks(ids: List<Int>) {
        client.post<Unit, IdsRequest>("/admin/queue/batch/delete", IdsRequest(ids))
    }

    /** `POST /admin/queue/cleanup`：删除 not_after 之前、符合类型/状态的任务记录 */
    @Throws(Exception::class)
    suspend fun cleanupTasks(request: TaskCleanupRequest) {
        client.post<Unit, TaskCleanupRequest>("/admin/queue/cleanup", request)
    }

    /** `GET /admin/queue/metrics`：各队列统计 */
    @Throws(Exception::class)
    suspend fun queueMetrics(): List<QueueMetric> =
        client.call(HttpMethod.Get, "/admin/queue/metrics", ListSerializer(QueueMetric.serializer()))

    // ---- 节点 ----

    @Throws(Exception::class)
    suspend fun nodes(request: AdminListRequest): AdminNodeListResponse = client.post("/admin/node", request)

    @Throws(Exception::class)
    suspend fun node(id: Int): AdminNode = client.get("/admin/node/$id")

    /**
     * `POST /admin/node/test`：连通性测试。服务端需要节点的 server 与 slave_key，
     * 模型里不保存 slave_key，因此先取原始节点 JSON 再原样提交。失败时抛 40001（原因在 msg）。
     */
    @Throws(Exception::class)
    suspend fun testNode(id: Int) {
        val raw = client.call(HttpMethod.Get, "/admin/node/$id", JsonObject.serializer())
        client.post<Unit, JsonObject>("/admin/node/test", buildJsonObject { put("node", raw) })
    }
}
