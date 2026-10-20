package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/**
 * `POST /file/url`（`service/explorer/file.go:FileURLService`）。
 * - [redirect] 仅单个 uri 有效且服务端直接 302，本客户端不使用
 * - [entity] 指定版本实体（对所有 uri 生效）
 * - [skipError] 为 true 时部分失败仍成功，失败项的 url 为空串
 * - [archive] 为 true 返回打包下载地址（需 archive_download 权限）
 */
@Serializable
data class FileUrlRequest(
    val uris: List<String>,
    val download: Boolean = false,
    val redirect: Boolean = false,
    val entity: String? = null,
    @SerialName("use_primary_site_url") val usePrimarySiteUrl: Boolean = false,
    @SerialName("skip_error") val skipError: Boolean = false,
    val archive: Boolean = false,
    @SerialName("no_cache") val noCache: Boolean = false,
)

@Serializable
data class FileUrlResponse(
    /** 与请求 uris 一一对应 */
    val urls: List<FileUrlEntry> = emptyList(),
    /** 所有 URL 中最早的过期时间；可能为 null（不过期） */
    val expires: CrInstant? = null,
)

@Serializable
data class FileUrlEntry(
    val url: String = "",
    /** 仅浏览器中转下载（OneDrive StreamSaver）时有值，为文件名 */
    @SerialName("stream_saver_display_name") val streamSaverDisplayName: String? = null,
)
