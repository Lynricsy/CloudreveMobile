package plus.ling.opencloudreve.core.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.CloudreveJson
import plus.ling.opencloudreve.core.api.endpoints.FileApi
import plus.ling.opencloudreve.core.api.endpoints.SourceLinksResult
import plus.ling.opencloudreve.core.db.OpenCloudreveDatabase
import plus.ling.opencloudreve.core.model.ArchiveListResponse
import plus.ling.opencloudreve.core.model.CreateFileRequest
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.ExplorerView
import plus.ling.opencloudreve.core.model.FileHashResponse
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileUrlRequest
import plus.ling.opencloudreve.core.model.FileUrlResponse
import plus.ling.opencloudreve.core.model.FullTextSearchResponse
import plus.ling.opencloudreve.core.model.ListPage
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.MetadataPatch
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.ViewerSessionRequest
import plus.ling.opencloudreve.core.model.ViewerSessionResponse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 单个账户的文件仓库：列目录（含 [ListingPager] 分页累积与 `ListingCache`）、文件信息、全部文件操作、
 * 下载/缩略图地址。所有 uri 参数为 `cloudreve://` 字符串。
 *
 * 由 `DataRepositories.files(accountId)` 按账户缓存，同一账户的各 VM 共享缩略图缓存。
 */
class FileRepository internal constructor(
    val accountId: String,
    private val clients: ApiClientProvider,
    private val database: OpenCloudreveDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private fun api() = FileApi(clients.client(accountId))

    // ---- 列目录 ----

    /**
     * 取一页（[nextPageToken] 游标模式，[page] 页码模式，均为空即第一页）。
     * 非搜索的第一页写入 `ListingCache`。
     */
    @Throws(Exception::class)
    suspend fun listPage(
        uri: String,
        pageSize: Int? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
        page: Int? = null,
        nextPageToken: String? = null,
    ): ListPage {
        val result = api().list(
            uri = uri,
            page = page,
            pageSize = pageSize,
            orderBy = orderBy,
            orderDirection = orderDirection,
            nextPageToken = nextPageToken,
        )
        if (nextPageToken == null && (page == null || page == 0)) writeListingCache(uri, result)
        return result
    }

    /** 新建分页累积器（列表页、文件夹选择器、系统文件集成共用） */
    fun pager(
        uri: String,
        pageSize: Int? = null,
        orderBy: String? = null,
        orderDirection: String? = null,
    ): ListingPager = ListingPager(this, uri, pageSize, orderBy, orderDirection)

    /** 读取缓存的目录列表（不发请求）；没有或解析失败返回 null */
    @Throws(Exception::class)
    suspend fun cachedListing(uri: String): ListPage? = withContext(ioDispatcher) {
        val row = database.cacheQueries.selectListing(accountId, uri).executeAsOneOrNull() ?: return@withContext null
        runCatching { CloudreveJson.decodeFromString(ListPage.serializer(), row.json) }.getOrNull()
    }

    /** 写入目录缓存（搜索结果不缓存） */
    internal suspend fun writeListingCache(uri: String, page: ListPage) {
        if (CrUri.parseOrNull(uri)?.isSearch() == true) return
        val json = CloudreveJson.encodeToString(ListPage.serializer(), page)
        withContext(ioDispatcher) {
            database.cacheQueries.putListing(accountId, uri, json, clock().toEpochMilliseconds())
        }
    }

    // ---- 搜索历史 ----

    /** 最近的搜索关键词（新到旧） */
    @Throws(Exception::class)
    suspend fun searchHistory(limit: Int = 20): List<String> = withContext(ioDispatcher) {
        database.cacheQueries.selectRecentSearches(accountId, limit.toLong()).executeAsList().map { it.query }
    }

    /** 记录一次搜索（同一关键词只保留一条并更新时间） */
    @Throws(Exception::class)
    suspend fun saveSearch(query: String) {
        withContext(ioDispatcher) { database.cacheQueries.putSearch(accountId, query, clock().toEpochMilliseconds()) }
    }

    @Throws(Exception::class)
    suspend fun deleteSearch(query: String) {
        withContext(ioDispatcher) { database.cacheQueries.deleteSearch(accountId, query) }
    }

    @Throws(Exception::class)
    suspend fun clearSearchHistory() {
        withContext(ioDispatcher) { database.cacheQueries.deleteSearchesForAccount(accountId) }
    }

    // ---- 信息 ----

    @Throws(Exception::class)
    suspend fun info(uri: String, extended: Boolean = false, folderSummary: Boolean = false): FileItem =
        api().info(uri = uri, extended = extended, folderSummary = folderSummary)

    @Throws(Exception::class)
    suspend fun infoById(id: String, extended: Boolean = false, folderSummary: Boolean = false): FileItem =
        api().info(id = id, extended = extended, folderSummary = folderSummary)

    /** 获取 URL/缩略图/信息/预览时应使用的 URI（`sys:shared_redirect` 优先） */
    fun resolveLinkedUri(file: FileItem): String = file.linkedUri

    /**
     * 打开前解析：软链接文件按链接目标重新取信息（拿到目标的 `primary_entity` 等），普通文件原样返回。
     */
    @Throws(Exception::class)
    suspend fun resolveForOpen(file: FileItem): FileItem = if (file.isSymbolic) info(file.linkedUri) else file

    // ---- 创建与修改 ----

    /** 在 [parentUri] 下新建文件夹；重名返回 40004 */
    @Throws(Exception::class)
    suspend fun createFolder(parentUri: String, name: String): FileItem =
        api().create(childUri(parentUri, name), CreateFileRequest.TYPE_FOLDER, errOnConflict = true)

    /** 在 [parentUri] 下新建空文件；重名返回 40004 */
    @Throws(Exception::class)
    suspend fun createFile(parentUri: String, name: String): FileItem =
        api().create(childUri(parentUri, name), CreateFileRequest.TYPE_FILE, errOnConflict = true)

    @Throws(Exception::class)
    suspend fun rename(uri: String, newName: String): FileItem = api().rename(uri, newName)

    @Throws(Exception::class)
    suspend fun move(uris: List<String>, dst: String) = api().move(uris, dst, copy = false)

    /** 复制；也用于“保存分享到我的文件”（40059 = 自己的分享） */
    @Throws(Exception::class)
    suspend fun copy(uris: List<String>, dst: String) = api().move(uris, dst, copy = true)

    /** 删除：默认进回收站；[skipSoftDelete] 彻底删除；[unlink] 仅解除链接（需 advance_delete） */
    @Throws(Exception::class)
    suspend fun delete(uris: List<String>, unlink: Boolean = false, skipSoftDelete: Boolean = false) =
        api().delete(uris, unlink, skipSoftDelete)

    @Throws(Exception::class)
    suspend fun restore(uris: List<String>) = api().restore(uris)

    @Throws(Exception::class)
    suspend fun emptyTrash() = api().emptyTrash()

    /** 强制解锁（40073 冲突项的 token） */
    @Throws(Exception::class)
    suspend fun unlock(tokens: List<String>) = api().unlock(tokens)

    @Throws(Exception::class)
    suspend fun patchMetadata(uris: List<String>, patches: List<MetadataPatch>) = api().patchMetadata(uris, patches)

    @Throws(Exception::class)
    suspend fun pin(uri: String, name: String? = null) = api().pin(uri, name)

    @Throws(Exception::class)
    suspend fun unpin(uri: String, name: String? = null) = api().unpin(uri, name)

    /** 同步目录视图设置；[view] 为 null 删除 */
    @Throws(Exception::class)
    suspend fun patchView(uri: String, view: ExplorerView?) = api().patchView(uri, view)

    /** 目录挂载存储策略；空串恢复继承 */
    @Throws(Exception::class)
    suspend fun mountPolicy(uri: String, policyId: String): StoragePolicy = api().mountPolicy(uri, policyId)

    @Throws(Exception::class)
    suspend fun setVersion(uri: String, version: String) = api().setVersion(uri, version)

    @Throws(Exception::class)
    suspend fun deleteVersion(uri: String, version: String) = api().deleteVersion(uri, version)

    /** 创建永久直链（40081 部分成功不抛，失败项在结果里） */
    @Throws(Exception::class)
    suspend fun createSourceLinks(uris: List<String>): SourceLinksResult = api().source(uris)

    @Throws(Exception::class)
    suspend fun deleteSourceLink(id: String) = api().deleteSource(id)

    @Throws(Exception::class)
    suspend fun hash(uris: List<String>, compute: Boolean = false): FileHashResponse = api().hash(uris, compute)

    @Throws(Exception::class)
    suspend fun fullTextSearch(query: String, offset: Int = 0): FullTextSearchResponse =
        api().fullTextSearch(query, offset)

    @Throws(Exception::class)
    suspend fun archiveList(uri: String, entity: String? = null, textEncoding: String? = null): ArchiveListResponse =
        api().archiveList(uri, entity, textEncoding)

    /** 保存文本内容；[previous] 为打开时的 `primary_entity`（40076 版本过期、40049 超限、40073 锁冲突） */
    @Throws(Exception::class)
    suspend fun putContent(uri: String, bytes: ByteArray, previous: String? = null): FileItem =
        api().putContent(uri, bytes, previous)

    @Throws(Exception::class)
    suspend fun viewerSession(request: ViewerSessionRequest): ViewerSessionResponse = api().viewerSession(request)

    // ---- 下载地址 ----

    /** `POST /file/url`；[uris] 应已是链接目标（见 [resolveLinkedUri]） */
    @Throws(Exception::class)
    suspend fun fileUrls(
        uris: List<String>,
        download: Boolean = false,
        entity: String? = null,
        usePrimarySiteUrl: Boolean = false,
        archive: Boolean = false,
        contextHint: String? = null,
    ): FileUrlResponse = api().url(
        FileUrlRequest(
            uris = uris,
            download = download,
            entity = entity,
            usePrimarySiteUrl = usePrimarySiteUrl,
            archive = archive,
        ),
        contextHint,
    )

    /** 单个文件的地址（自动使用链接目标） */
    @Throws(Exception::class)
    suspend fun fileUrl(
        file: FileItem,
        download: Boolean = false,
        entity: String? = null,
        contextHint: String? = null,
    ): FileUrlResponse = fileUrls(listOf(resolveLinkedUri(file)), download, entity, contextHint = contextHint)

    // ---- 缩略图 ----

    private val thumbMutex = Mutex()
    private val thumbCache = HashMap<String, ThumbEntry>()
    private val thumbDisabled = HashSet<String>()

    /**
     * 缩略图签名地址（按前端规则）：
     * - 目录、带 `thumb:disabled` 元数据或本地已标记禁用的文件直接返回 null，不发请求；
     * - 按文件 path 缓存到 `expires` 前 [THUMB_REFRESH_MARGIN]（无 `expires` 时会话内一直有效）；
     * - 任何错误（含 40077、-1）或空 url 都在本地标记禁用并返回 null。
     */
    @Throws(Exception::class)
    suspend fun thumbUrl(file: FileItem, contextHint: String? = null): String? {
        if (file.isFolder || file.thumbDisabled) return null
        val key = file.path
        val now = clock()
        thumbMutex.withLock {
            if (key in thumbDisabled) return null
            thumbCache[key]?.let { entry ->
                if (entry.expires == null || entry.expires - now > THUMB_REFRESH_MARGIN) return entry.url
            }
        }
        val response = try {
            api().thumb(resolveLinkedUri(file), contextHint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        thumbMutex.withLock {
            if (response == null || response.url.isEmpty()) {
                thumbDisabled += key
                thumbCache -= key
                return null
            }
            thumbCache[key] = ThumbEntry(response.url, response.expires)
            return response.url
        }
    }

    /** 本地是否已把该文件标记为无缩略图 */
    @Throws(Exception::class)
    suspend fun isThumbDisabled(file: FileItem): Boolean =
        file.isFolder || file.thumbDisabled || thumbMutex.withLock { file.path in thumbDisabled }

    /** 重置缩略图：移除服务端 `thumb:disabled` 元数据并清除本地标记 */
    @Throws(Exception::class)
    suspend fun resetThumbnails(uris: List<String>) {
        patchMetadata(uris, listOf(MetadataPatch(MetadataKeys.THUMB_DISABLED, remove = true)))
        thumbMutex.withLock {
            uris.forEach {
                thumbDisabled -= it
                thumbCache -= it
            }
        }
    }

    private class ThumbEntry(val url: String, val expires: Instant?)

    companion object {
        /** 缩略图/签名地址到期前提前刷新的余量 */
        val THUMB_REFRESH_MARGIN = 30.seconds

        /** `parent/name` 子项 URI */
        fun childUri(parentUri: String, name: String): String = CrUri.parse(parentUri).join(name).toString()
    }
}
