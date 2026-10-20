package plus.ling.opencloudreve.core.api.endpoints

import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.model.DavAccount
import plus.ling.opencloudreve.core.model.DavAccountListResponse
import plus.ling.opencloudreve.core.model.DavAccountRequest

/** WebDAV 账号接口（`/devices/dav`） */
class DeviceApi(private val client: ApiClient) {

    /** `GET /devices/dav`（游标分页，[pageSize] 10–100） */
    @Throws(Exception::class)
    suspend fun davAccounts(pageSize: Int = 50, nextPageToken: String? = null): DavAccountListResponse =
        client.get("/devices/dav", mapOf("page_size" to pageSize, "next_page_token" to nextPageToken))

    /** `PUT /devices/dav`：新建账号，密码由服务端生成 */
    @Throws(Exception::class)
    suspend fun createDavAccount(request: DavAccountRequest): DavAccount = client.put("/devices/dav", request)

    /** `PATCH /devices/dav/:id`：整体更新名称、根目录与选项（不改密码） */
    @Throws(Exception::class)
    suspend fun updateDavAccount(id: String, request: DavAccountRequest): DavAccount =
        client.patch("/devices/dav/$id", request)

    /** `DELETE /devices/dav/:id` */
    @Throws(Exception::class)
    suspend fun deleteDavAccount(id: String) {
        client.deleteNoBody<Unit>("/devices/dav/$id")
    }

    /** WebDAV 服务地址（HTTP Basic：用户名为账号邮箱，密码为 [DavAccount.password]） */
    fun davUrl(): String = client.siteUrl.trimEnd('/') + "/dav"
}
