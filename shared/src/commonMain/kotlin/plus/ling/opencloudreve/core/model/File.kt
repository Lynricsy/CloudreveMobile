package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/** 文件类型（`types.FileType`） */
object FileType {
    const val FILE = 0
    const val FOLDER = 1
}

/** 实体类型（`types.EntityType`） */
object EntityType {
    const val VERSION = 0
    const val THUMBNAIL = 1
    const val LIVE_PHOTO = 2
}

/** 常用元数据 key（`dbfs/file.go`、前端 `api/explorer.ts:Metadata`） */
object MetadataKeys {
    const val SHARED_REDIRECT = "sys:shared_redirect"
    const val SHARED_OWNER = "sys:shared_owner"
    const val RESTORE_URI = "sys:restore_uri"
    const val EXPECTED_COLLECT_TIME = "sys:expected_collect_time"
    const val UPLOAD_SESSION_ID = "sys:upload_session_id"
    const val THUMB_DISABLED = "thumb:disabled"
    const val ICON_COLOR = "customize:icon_color"
    const val EMOJI = "customize:emoji"
    const val LIVE_PHOTO = "customize:live_photo"
    const val TAG_PREFIX = "tag:"
    const val CUSTOM_PROPS_PREFIX = "props:"
    const val EXIF_PREFIX = "exif:"
    const val MUSIC_PREFIX = "music:"
    const val STREAM_PREFIX = "stream:"
    const val GEOCODING_PREFIX = "geocoding:"
}

/**
 * 文件/目录（`service/explorer/response.go:FileResponse`）。
 * 目录列表中每项的 [capability] 与列表 `props.capability` 相同。
 */
@Serializable
data class FileItem(
    /** [FileType.FILE] 或 [FileType.FOLDER] */
    val type: Int = FileType.FILE,
    val id: String = "",
    /** 显示名（分享/回收站中可能与真实名不同） */
    val name: String = "",
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    /** 字节；目录为 0，精确值见 [folderSummary] */
    val size: Long = 0,
    /** 公开元数据（服务端可能给 null，这里统一为空表） */
    val metadata: Map<String, String> = emptyMap(),
    /** 完整 `cloudreve://` URI */
    val path: String = "",
    /** 当前用户作为所有者且已分享 */
    val shared: Boolean = false,
    val capability: BoolSet? = null,
    /** 是否为当前用户所有（缺失即 false） */
    val owned: Boolean = false,
    @SerialName("primary_entity") val primaryEntity: String? = null,
    /** 以下三项仅在 `hash=true` 时出现 */
    val sha256: String? = null,
    @SerialName("hash_status") val hashStatus: String? = null,
    @SerialName("hash_reason") val hashReason: String? = null,
    /** 仅 `GET /file/info?folder_summary=true` */
    @SerialName("folder_summary") val folderSummary: FolderSummary? = null,
    /** 仅 `GET /file/info?extended=true` */
    @SerialName("extended_info") val extendedInfo: ExtendedInfo? = null,
) {
    val isFolder: Boolean get() = type == FileType.FOLDER

    /** 软链接文件（来自分享/他人数据的符号链接） */
    val isSymbolic: Boolean get() = MetadataKeys.SHARED_REDIRECT in metadata

    /** 获取 URL、缩略图、信息、预览时应使用的 URI：`sys:shared_redirect` 优先（前端 `getFileLinkedUri`） */
    val linkedUri: String get() = metadata[MetadataKeys.SHARED_REDIRECT] ?: path

    /** 是否显式禁用了缩略图（key 存在即禁用） */
    val thumbDisabled: Boolean get() = MetadataKeys.THUMB_DISABLED in metadata

    /** 扩展名（小写，不含点）；目录或无扩展名返回空串 */
    val extension: String
        get() = if (isFolder) "" else name.substringAfterLast('.', "").lowercase()

    /** 解析后的 [path]；服务端给的零值对象（如根的 parent）可能为空，返回 null */
    fun uri(): CrUri? = CrUri.parseOrNull(path)

    fun can(capabilityBit: Int): Boolean = capability?.has(capabilityBit) == true
}

/** 目录统计（`fs.FolderSummary`）；[completed] 为 false 表示仍在统计 */
@Serializable
data class FolderSummary(
    val size: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
    val completed: Boolean = false,
    @SerialName("calculated_at") val calculatedAt: CrInstant? = null,
)

/** `GET /file/info?extended=true` 的扩展信息（`response.go:ExtendedInfo`） */
@Serializable
data class ExtendedInfo(
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
    /** 目录且未单独挂载策略时为 true */
    @SerialName("storage_policy_inherited") val storagePolicyInherited: Boolean = false,
    @SerialName("storage_used") val storageUsed: Long = 0,
    /** 仅所有者可见 */
    val shares: List<Share> = emptyList(),
    val entities: List<FileEntity> = emptyList(),
    val view: ExplorerView? = null,
    @SerialName("direct_links") val directLinks: List<DirectLink> = emptyList(),
)

/** 文件实体（版本/缩略图/实况照片，`response.go:Entity`） */
@Serializable
data class FileEntity(
    val id: String,
    val size: Long = 0,
    /** 见 [EntityType] */
    val type: Int = EntityType.VERSION,
    @SerialName("created_at") val createdAt: CrInstant? = null,
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
    /** 脱敏用户（无邮箱） */
    @SerialName("created_by") val createdBy: User? = null,
    /** `aes-256-ctr` 或缺失 */
    @SerialName("encrypted_with") val encryptedWith: String? = null,
    val sha256: String? = null,
)

/** 永久直链（`response.go:DirectLink`） */
@Serializable
data class DirectLink(
    val id: String,
    val url: String = "",
    val downloaded: Int = 0,
    @SerialName("created_at") val createdAt: CrInstant? = null,
)

/** `PUT /file/source` 结果项（`response.go:DirectLinkResponse`） */
@Serializable
data class SourceLink(
    val link: String = "",
    @SerialName("file_url") val fileUrl: String = "",
)

/** 目录视图设置（`types.ExplorerView`）；`page_size` 服务端要求 ≥ 50 */
@Serializable
data class ExplorerView(
    @SerialName("page_size") val pageSize: Int = 0,
    val order: String? = null,
    /** `asc` / `desc` */
    @SerialName("order_direction") val orderDirection: String? = null,
    /** `list` / `grid` / `gallery` */
    val view: String? = null,
    val thumbnail: Boolean = false,
    @SerialName("gallery_width") val galleryWidth: Int? = null,
    val columns: List<ListViewColumn>? = null,
) {
    companion object {
        const val VIEW_LIST = "list"
        const val VIEW_GRID = "grid"
        const val VIEW_GALLERY = "gallery"
        const val MIN_PAGE_SIZE = 50
    }
}

/** 列表视图的列（`types.ListViewColumn`；[type] 为前端 ColumType 枚举值） */
@Serializable
data class ListViewColumn(
    val type: Int = 0,
    val width: Int? = null,
    val props: ListViewColumnProps? = null,
)

@Serializable
data class ListViewColumnProps(
    @SerialName("metadata_key") val metadataKey: String? = null,
    @SerialName("custom_props_id") val customPropsId: String? = null,
)

/** 目录导航属性（`fs.NavigatorProps`）；搜索/分享时排序选项为 null */
@Serializable
data class NavigatorProps(
    val capability: BoolSet? = null,
    @SerialName("max_page_size") val maxPageSize: Int = 0,
    @SerialName("order_by_options") val orderByOptions: List<String>? = null,
    @SerialName("order_direction_options") val orderDirectionOptions: List<String>? = null,
)

/** `GET /file` 列目录响应（`response.go:ListResponse`） */
@Serializable
data class ListPage(
    val files: List<FileItem> = emptyList(),
    /** 当前目录自身；服务端总会给出（可能是零值对象，此时 [FileItem.id] 为空） */
    val parent: FileItem? = null,
    val pagination: Pagination = Pagination(),
    val props: NavigatorProps = NavigatorProps(),
    /** 后续 `POST /file/url`、`GET /file/thumb` 可通过 `X-Cr-Context-Hint` 带回（TTL 5 分钟） */
    @SerialName("context_hint") val contextHint: String? = null,
    @SerialName("recursion_limit_reached") val recursionLimitReached: Boolean = false,
    @SerialName("mixed_type") val mixedType: Boolean = false,
    /** 单文件分享根：[files] 只有该文件 */
    @SerialName("single_file_view") val singleFileView: Boolean = false,
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
    val view: ExplorerView? = null,
)

/** `GET /file` 排序字段 */
object FileOrderBy {
    const val NAME = "name"
    const val SIZE = "size"
    const val UPDATED_AT = "updated_at"
    const val CREATED_AT = "created_at"
}

/** `POST /file/create` */
@Serializable
data class CreateFileRequest(
    val uri: String,
    /** `file` / `folder` */
    val type: String,
    val metadata: Map<String, String>? = null,
    /** 为 true 时重名返回 40004；否则返回已存在对象 */
    @SerialName("err_on_conflict") val errOnConflict: Boolean = false,
) {
    companion object {
        const val TYPE_FILE = "file"
        const val TYPE_FOLDER = "folder"
    }
}

/** `POST /file/rename` */
@Serializable
data class RenameFileRequest(
    val uri: String,
    @SerialName("new_name") val newName: String,
)

/** `POST /file/move`（[copy] 为 true 时复制；复制仅限 `my` 文件系统内） */
@Serializable
data class MoveFileRequest(
    val uris: List<String>,
    val dst: String,
    val copy: Boolean = false,
)

/** `DELETE /file`、`POST /file/restore`（`DeleteFileService`） */
@Serializable
data class DeleteFileRequest(
    val uris: List<String>,
    /** 仅解除链接（需 advance_delete 权限） */
    val unlink: Boolean = false,
    /** 彻底删除，不进回收站 */
    @SerialName("skip_soft_delete") val skipSoftDelete: Boolean = false,
)

/** `DELETE /file/lock` */
@Serializable
data class UnlockFileRequest(val tokens: List<String>)

/** 单条元数据变更（`fs.MetadataPatch`；`private` 不可为 true，故不提供） */
@Serializable
data class MetadataPatch(
    val key: String,
    val value: String = "",
    val remove: Boolean = false,
)

/** `PATCH /file/metadata` */
@Serializable
data class PatchMetadataRequest(
    val uris: List<String>,
    val patches: List<MetadataPatch>,
)

/** `PUT /file/pin`、`DELETE /file/pin` */
@Serializable
data class PinFileRequest(
    val uri: String,
    val name: String? = null,
)

/** `PATCH /file/view`；[view] 为 null 表示删除（字段省略，服务端同样视为 nil） */
@Serializable
data class PatchViewRequest(
    val uri: String,
    val view: ExplorerView?,
)

/** `PATCH /file/policy`；[policyId] 为空串表示恢复继承 */
@Serializable
data class MountPolicyRequest(
    val uri: String,
    @SerialName("policy_id") val policyId: String,
)

/** `POST /file/version/current`、`DELETE /file/version` */
@Serializable
data class FileVersionRequest(
    val uri: String,
    /** 实体 hashid */
    val version: String,
)

/** `POST /file/hash` */
@Serializable
data class FileHashRequest(
    val uris: List<String>,
    /** 为尚未计算的文件创建计算任务（仅“我的文件”） */
    val compute: Boolean = false,
)

@Serializable
data class FileHashResponse(val items: List<FileHashItem> = emptyList())

/** [status]：ready/pending/failed/missing/uploading/unsupported/not_file/error */
@Serializable
data class FileHashItem(
    val uri: String = "",
    @SerialName("entity_id") val entityId: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val status: String = "",
    val reason: String? = null,
    /** 仅 status 为 error 时 */
    val code: Int? = null,
)

/** `POST /file/hash/search`（1–100 个 64 位小写十六进制） */
@Serializable
data class HashSearchRequest(val sha256: List<String>)

@Serializable
data class HashSearchResponse(
    val matches: Map<String, List<HashMatch>> = emptyMap(),
    val truncated: Boolean = false,
    val coverage: HashCoverage? = null,
)

@Serializable
data class HashMatch(
    val uri: String = "",
    val id: String = "",
    val size: Long = 0,
)

@Serializable
data class HashCoverage(
    val hashed: Int = 0,
    val computable: Int = 0,
    val unsupported: Int = 0,
)

/** `GET /file/search` 全文搜索结果 */
@Serializable
data class FullTextSearchResponse(
    val hits: List<FullTextHit> = emptyList(),
    val total: Long = 0,
)

@Serializable
data class FullTextHit(
    val file: FileItem,
    /** 命中片段 */
    val content: String = "",
)

/** `GET /file/thumb` */
@Serializable
data class ThumbResponse(
    val url: String = "",
    val expires: CrInstant? = null,
)

/** `GET /file/archive` */
@Serializable
data class ArchiveListResponse(val files: List<ArchivedFile> = emptyList())

/** 压缩包内条目；[name] 为完整路径（可能以 `/` 开头），父目录可能缺失 */
@Serializable
data class ArchivedFile(
    val name: String = "",
    val size: Long = 0,
    @SerialName("updated_at") val updatedAt: CrInstant? = null,
    @SerialName("is_directory") val isDirectory: Boolean = false,
)

/** `PUT /file/viewerSession` */
@Serializable
data class ViewerSessionRequest(
    val uri: String,
    @SerialName("viewer_id") val viewerId: String,
    /** `view` / `edit` */
    @SerialName("preferred_action") val preferredAction: String,
    val version: String? = null,
) {
    companion object {
        const val ACTION_VIEW = "view"
        const val ACTION_EDIT = "edit"
    }
}

@Serializable
data class ViewerSessionResponse(
    val session: ViewerSession,
    /** 仅 wopi 类型 */
    @SerialName("wopi_src") val wopiSrc: String? = null,
)

@Serializable
data class ViewerSession(
    val id: String = "",
    @SerialName("access_token") val accessToken: String = "",
    /** Unix 毫秒 */
    val expires: Long = 0,
)

/** `GET /file/events` SSE 中 `event` 事件的数据（`eventhub.Event`） */
@Serializable
data class ExplorerEvent(
    /** create / modify / rename / delete */
    val type: String,
    @SerialName("file_id") val fileId: String = "",
    /** 相对被订阅目录的路径，以 `/` 开头 */
    val from: String = "",
    /** 仅 rename */
    val to: String = "",
) {
    companion object {
        const val TYPE_CREATE = "create"
        const val TYPE_MODIFY = "modify"
        const val TYPE_RENAME = "rename"
        const val TYPE_DELETE = "delete"
    }
}
