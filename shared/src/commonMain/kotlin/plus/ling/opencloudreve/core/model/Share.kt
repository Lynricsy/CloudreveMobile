package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/**
 * 分享（`service/explorer/response.go:Share`）。
 * 未解锁（需要密码且未提供）时只有 id/name/visited/unlocked/expired/url/created_at/owner/size/source_type/password_protected。
 */
@Serializable
data class Share(
    val id: String,
    val name: String? = null,
    /** 仅解锁后；null 表示不限 */
    @SerialName("remain_downloads") val remainDownloads: Int? = null,
    val visited: Int = 0,
    val downloaded: Int = 0,
    val expires: CrInstant? = null,
    val unlocked: Boolean = false,
    @SerialName("password_protected") val passwordProtected: Boolean = false,
    /** 0 文件、1 目录 */
    @SerialName("source_type") val sourceType: Int? = null,
    val owner: User? = null,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    val expired: Boolean = false,
    /** 分享链接；仅解锁时包含密码 */
    val url: String = "",
    @SerialName("show_readme") val showReadme: Boolean = false,
    /** 仅解锁的单文件分享非 0 */
    val size: Long = 0,
    /** 以下仅所有者可见 */
    @SerialName("is_private") val isPrivate: Boolean = false,
    val password: String? = null,
    @SerialName("share_view") val shareView: Boolean = false,
    /** 仅 `owner_extended=true` 且为所有者 */
    @SerialName("source_uri") val sourceUri: String? = null,
) {
    val isFolder: Boolean get() = sourceType == FileType.FOLDER

    /** 浏览该分享的根 URI（带已知密码） */
    fun rootUri(password: String? = this.password): CrUri = CrUri.share(id, password)
}

/**
 * `PUT /share`（新建）与 `POST /share/:id`（编辑）请求体（`service/share/manage.go:ShareCreateService`）。
 * - [password] 最多 32 位字母数字；[isPrivate] 为 true 且留空时服务端生成 8 位小写字母密码
 * - [downloads] 剩余下载次数，0 不限
 * - [expire] 相对秒数，0 不过期
 */
@Serializable
data class ShareCreateRequest(
    val uri: String,
    @SerialName("is_private") val isPrivate: Boolean = false,
    val password: String? = null,
    val downloads: Int = 0,
    val expire: Int = 0,
    @SerialName("share_view") val shareView: Boolean = false,
    @SerialName("show_readme") val showReadme: Boolean = false,
)

/** `GET /share`、`GET /user/shares/:id` */
@Serializable
data class ShareListResponse(
    val shares: List<Share> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/** `GET /share` 排序字段（其它值按 id） */
object ShareOrderBy {
    const val VIEWS = "views"
    const val DOWNLOADS = "downloads"
    const val REMAIN_DOWNLOADS = "remain_downloads"
}
