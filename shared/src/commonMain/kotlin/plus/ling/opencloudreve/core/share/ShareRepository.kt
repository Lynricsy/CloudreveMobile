package plus.ling.opencloudreve.core.share

import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.endpoints.ShareApi
import plus.ling.opencloudreve.core.api.endpoints.UserApi
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.model.ShareCreateRequest
import plus.ling.opencloudreve.core.model.ShareListResponse

/** 单个账户的分享仓库：我的分享（游标列表）、新建/编辑/删除、访问分享信息、用户公开分享 */
class ShareRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
) {
    private fun api() = ShareApi(clients.client(accountId))

    /** 我的分享（游标分页：把上一页 `pagination.next_token` 作为 [nextPageToken]） */
    @Throws(Exception::class)
    suspend fun list(
        pageSize: Int = 50,
        nextPageToken: String? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
    ): ShareListResponse = api().list(pageSize, nextPageToken, orderBy, orderDirection)

    /** 新建分享，返回分享链接（私密分享时含密码） */
    @Throws(Exception::class)
    suspend fun create(request: ShareCreateRequest): String = api().create(request)

    /** 编辑分享，返回新链接 */
    @Throws(Exception::class)
    suspend fun update(id: String, request: ShareCreateRequest): String = api().update(id, request)

    @Throws(Exception::class)
    suspend fun delete(ids: List<String>) {
        val api = api()
        if (ids.size == 1) api.delete(ids.first()) else api.deleteBatch(ids)
    }

    /** 分享信息；密码错误不报错而是 `unlocked=false` */
    @Throws(Exception::class)
    suspend fun info(
        id: String,
        password: String? = null,
        countViews: Boolean = false,
        ownerExtended: Boolean = false,
    ): Share = api().info(id, password, countViews, ownerExtended)

    /** 某用户的公开分享 */
    @Throws(Exception::class)
    suspend fun userShares(
        userId: String,
        pageSize: Int = 50,
        nextPageToken: String? = null,
    ): ShareListResponse = UserApi(clients.client(accountId)).shares(userId, pageSize, nextPageToken)
}
