package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/** WebDAV 账号选项位（`types.DavAccountOption`） */
object DavAccountOption {
    const val READONLY = 0
    const val PROXY = 1
    const val DISABLE_SYS_FILES = 2
}

/**
 * WebDAV 账号（`service/setting/response.go:DavAccount`）。
 * 访问地址 `<站点>/dav`，HTTP Basic 用户名为账号邮箱，密码为 [password]（服务端生成的明文）。
 */
@Serializable
data class DavAccount(
    val id: String,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    val name: String = "",
    /** 根目录 `cloudreve://my/...` 或分享 URI */
    val uri: String = "",
    val password: String = "",
    val options: BoolSet? = null,
) {
    val readonly: Boolean get() = options?.has(DavAccountOption.READONLY) == true
    val proxy: Boolean get() = options?.has(DavAccountOption.PROXY) == true
    val disableSysFiles: Boolean get() = options?.has(DavAccountOption.DISABLE_SYS_FILES) == true
}

/** `GET /devices/dav` */
@Serializable
data class DavAccountListResponse(
    val accounts: List<DavAccount> = emptyList(),
    val pagination: Pagination = Pagination(),
)

/**
 * `PUT /devices/dav`（新建）与 `PATCH /devices/dav/:id`（整体更新，不改密码）。
 * [uri] 只能是 my 或 share 文件系统且不能带搜索参数；[proxy] 仅在用户组有 webdav_proxy 权限时生效。
 */
@Serializable
data class DavAccountRequest(
    val uri: String,
    val name: String,
    val readonly: Boolean = false,
    val proxy: Boolean = false,
    @SerialName("disable_sys_files") val disableSysFiles: Boolean = false,
)
