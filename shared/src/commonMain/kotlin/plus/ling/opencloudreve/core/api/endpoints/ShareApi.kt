package plus.ling.opencloudreve.core.api.endpoints

import plus.ling.opencloudreve.core.api.ApiClient
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.model.ShareCreateRequest
import plus.ling.opencloudreve.core.model.ShareListResponse
import plus.ling.opencloudreve.core.model.StringIdsRequest

/** 分享接口（`/share/…`）。浏览分享内容用 [FileApi] 与 `cloudreve://<id>[:<pw>]@share` URI */
class ShareApi(private val client: ApiClient) {

    /** `PUT /share`：新建分享，返回分享链接 `<站点>/s/<id>[/<密码>]` */
    @Throws(Exception::class)
    suspend fun create(request: ShareCreateRequest): String = client.put("/share", request)

    /** `POST /share/:id`：编辑分享（[ShareCreateRequest.uri] 必须是该分享的源文件），返回分享链接 */
    @Throws(Exception::class)
    suspend fun update(id: String, request: ShareCreateRequest): String = client.post("/share/$id", request)

    /**
     * `GET /share/info/:id`（无需登录）。密码错误不报错，返回 `unlocked=false` 的精简信息。
     * [countViews] 在打开分享时用一次；[ownerExtended] 仅所有者有效，返回 `source_uri`。
     */
    @Throws(Exception::class)
    suspend fun info(
        id: String,
        password: String? = null,
        countViews: Boolean = false,
        ownerExtended: Boolean = false,
    ): Share = client.get(
        "/share/info/$id",
        mapOf(
            "password" to password,
            "count_views" to if (countViews) true else null,
            "owner_extended" to if (ownerExtended) true else null,
        ),
    )

    /** `GET /share`：我的分享（游标分页，[pageSize] 10–100；[orderBy] 见 `ShareOrderBy`） */
    @Throws(Exception::class)
    suspend fun list(
        pageSize: Int = 50,
        nextPageToken: String? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
    ): ShareListResponse = client.get(
        "/share",
        mapOf(
            "page_size" to pageSize,
            "next_page_token" to nextPageToken,
            "order_by" to orderBy,
            "order_direction" to orderDirection,
        ),
    )

    /** `DELETE /share/:id` */
    @Throws(Exception::class)
    suspend fun delete(id: String) {
        client.deleteNoBody<Unit>("/share/$id")
    }

    /** `DELETE /share {ids}`：批量删除自己的分享 */
    @Throws(Exception::class)
    suspend fun deleteBatch(ids: List<String>) {
        client.delete<Unit, StringIdsRequest>("/share", StringIdsRequest(ids))
    }
}
