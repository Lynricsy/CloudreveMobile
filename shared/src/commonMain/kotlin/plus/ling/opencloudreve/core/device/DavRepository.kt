package plus.ling.opencloudreve.core.device

import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.endpoints.DeviceApi
import plus.ling.opencloudreve.core.model.DavAccount
import plus.ling.opencloudreve.core.model.DavAccountListResponse
import plus.ling.opencloudreve.core.model.DavAccountRequest

/** 单个账户的 WebDAV 设备账号仓库 */
class DavRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
) {
    private fun api() = DeviceApi(clients.client(accountId))

    /** 游标分页列表 */
    @Throws(Exception::class)
    suspend fun list(pageSize: Int = 50, nextPageToken: String? = null): DavAccountListResponse =
        api().davAccounts(pageSize, nextPageToken)

    /** 拉取全部账号（数量通常很少） */
    @Throws(Exception::class)
    suspend fun listAll(): List<DavAccount> {
        val all = ArrayList<DavAccount>()
        var token: String? = null
        do {
            val page = api().davAccounts(50, token)
            all += page.accounts
            token = page.pagination.nextToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return all
    }

    @Throws(Exception::class)
    suspend fun create(request: DavAccountRequest): DavAccount = api().createDavAccount(request)

    @Throws(Exception::class)
    suspend fun update(id: String, request: DavAccountRequest): DavAccount = api().updateDavAccount(id, request)

    @Throws(Exception::class)
    suspend fun delete(id: String) = api().deleteDavAccount(id)

    /** WebDAV 地址（`<站点>/dav`），用户名为账户邮箱 */
    @Throws(Exception::class)
    fun davUrl(): String = api().davUrl()
}
