package plus.ling.opencloudreve.documents

import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.auth.Account
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.files.ListingPager
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.SearchParams
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.transfer.TransferRuntime
import plus.ling.opencloudreve.transfer.UploadSources
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统文件集成（Storage Access Framework）：每个有效账户一个根，根目录为 `cloudreve://my`。
 *
 * - 文档 ID 用服务端稳定的文件 ID（[DocumentId]），改名/移动（含上级目录）后外部应用持有的授权仍有效；
 *   当前路径按需解析（内存缓存 → `GET /file/info?id=`，离线时查目录缓存），修改类操作总是取最新位置；
 * - 列目录先返回 `ListingCache` 并带 `EXTRA_LOADING`，后台拉取全部分页写回缓存后 `notifyChange`；无缓存时同步拉第一页；
 * - 读：代理文件描述符按 Range 请求签名地址（[RemoteFileReader]）；写：写入收件箱文件，交出描述符前记入
 *   [PendingWrites] 日志，关闭后交给传输队列上传（进程被回收也能恢复）；
 * - Binder 线程上的网络调用都有超时并响应 [CancellationSignal]（下载持有 OkHttp Call 直接取消），不会无限阻塞。
 *
 * 注意：ContentProvider 早于 `Application.onCreate` 创建并发布，所以每个入口先 [awaitGraph]。
 */
class CloudreveDocumentsProvider : DocumentsProvider() {
    private lateinit var authority: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已知对象（键为文档 ID），供 queryDocument/打开/缩略图/位置解析免请求查询 */
    private val items = LruCache<String, Entry>(ITEM_CACHE_SIZE)

    /** 目录能力位（键 `<accountId>|<目录 URI>`，来自列表 `props.capability`），算子项 flags 用 */
    private val dirCapabilities = ConcurrentHashMap<String, BoolSet>()
    private val listings = ConcurrentHashMap<String, ListingState>()
    private val capacityRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [resolvedAt] 为从服务端确认该位置的时刻（elapsedRealtime）；来自本地缓存为 0 */
    private class Entry(val file: FileItem, val dirCapability: BoolSet?, val resolvedAt: Long)

    private class ListingState {
        val loading = AtomicBoolean(false)
        @Volatile var loadedAt = 0L
        @Volatile var error: String? = null

        /** 最近一次列出时该目录的位置（写入/改名后按位置通知） */
        @Volatile var uri: CrUri? = null
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        authority = info.authority
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean {
        // 主线程消息在 Application.onCreate（依赖图启动）之后才执行
        Handler(Looper.getMainLooper()).post {
            watchAccounts()
            recoverPendingWrites()
        }
        return true
    }

    // ---- 根 ----

    override fun queryRoots(projection: Array<out String>?): Cursor {
        awaitGraph()
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.setNotificationUri(resolver(), rootsUri())
        for (account in activeAccounts()) {
            val capacity = CoreGraph.userSettings(account.id).capacity.value
            if (capacity == null) refreshCapacity(account.id)
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, account.id)
                add(Root.COLUMN_DOCUMENT_ID, DocumentId.root(account.id).encoded)
                add(Root.COLUMN_TITLE, rootTitle(account))
                add(Root.COLUMN_SUMMARY, account.host)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
                add(Root.COLUMN_AVAILABLE_BYTES, capacity?.let { (it.total - it.used).coerceAtLeast(0) })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Root.COLUMN_CAPACITY_BYTES, capacity?.total)
                }
            }
        }
        return cursor
    }

    // ---- 查询 ----

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        val account = account(id)
        val cursor = MatrixCursor(projection ?: DocumentRows.DEFAULT_DOCUMENT_PROJECTION)
        // 直接观察文档 URI 的消费者（如保存后重新查询大小的编辑器）在写回完成时收到通知
        cursor.setNotificationUri(resolver(), documentUri(documentId))
        if (id is DocumentId.Root) {
            val capability = items.get(documentId)?.file?.capability
            DocumentRows.addRoot(cursor, documentId, rootTitle(account), capability)
        } else {
            val entry = entry(id, signal = null)
            DocumentRows.addFile(cursor, documentId, entry.file, entry.dirCapability)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        awaitGraph()
        val parent = DocumentId.parse(parentDocumentId)
        val repo = files(parent)
        val cursor = MatrixCursor(projection ?: DocumentRows.DEFAULT_DOCUMENT_PROJECTION)
        val notifyUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        cursor.setNotificationUri(resolver(), notifyUri)
        val state = listings.getOrPut(parentDocumentId) { ListingState() }

        try {
            var dir = locate(parent, signal = null, fresh = false)
            state.uri = dir
            val cached = try {
                blocking { repo.cachedListing(dir.toString()) }
            } catch (e: Exception) {
                null
            }
            if (cached != null) {
                val fresh = state.loadedAt != 0L && SystemClock.elapsedRealtime() - state.loadedAt <= FRESH_MS
                addListing(cursor, parent, dir, cached.files, cached.props.capability, cached.parent, if (fresh) state.loadedAt else 0L)
                if (!fresh) loadAll(parent, dir, state, notifyUri, pager = null)
            } else {
                // 无缓存：同步取第一页，其余分页后台继续；按过期位置列失败时重新解析一次
                val (pager, snapshot) = try {
                    firstPage(repo, dir)
                } catch (e: ApiException) {
                    val moved = relocated(parent, dir) ?: throw e
                    dir = moved
                    state.uri = dir
                    firstPage(repo, dir)
                }
                addListing(cursor, parent, dir, snapshot.files, snapshot.props.capability, snapshot.parent, SystemClock.elapsedRealtime())
                if (snapshot.hasMore) {
                    loadAll(parent, dir, state, notifyUri, pager)
                } else {
                    state.loadedAt = SystemClock.elapsedRealtime()
                }
            }
        } catch (e: Exception) {
            state.error = describe(e)
        }

        val extras = Bundle()
        extras.putBoolean(DocumentsContract.EXTRA_LOADING, state.loading.get())
        state.error?.let {
            extras.putString(DocumentsContract.EXTRA_ERROR, context!!.getString(R.string.documents_load_failed, it))
            state.error = null
        }
        cursor.extras = extras
        return cursor
    }

    private fun firstPage(repo: FileRepository, dir: CrUri) = repo.pager(dir.toString(), pageSize = PAGE_SIZE).let { pager ->
        pager to blocking { pager.refresh() }
    }

    /**
     * 外部应用的目录授权（tree URI）据此判断访问范围：比较两者的当前位置。
     * 放行只依据 [RESOLVE_FRESH_MS] 内从服务端确认过的位置（对象移出授权目录后不按旧位置放行）；
     * 否定前再向服务端确认一次（刚改名/移动进来的对象不误拒）。
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        awaitGraph()
        val parent = DocumentId.parse(parentDocumentId)
        val child = DocumentId.parse(documentId)
        if (parent.accountId != child.accountId || child is DocumentId.Root) {
            false
        } else {
            DocumentId.isAncestor(locateRecent(parent), locateRecent(child)) ||
                DocumentId.isAncestor(locate(parent, signal = null, fresh = true), locate(child, signal = null, fresh = true))
        }
    } catch (e: Exception) {
        false
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        awaitGraph()
        val root = DocumentId.root(rootId)
        val repo = files(root)
        val cursor = MatrixCursor(projection ?: DocumentRows.DEFAULT_DOCUMENT_PROJECTION)
        val keyword = query.trim()
        if (keyword.isEmpty()) return cursor
        val searchUri = CrUri.my().withSearch(SearchParams(names = listOf(keyword), caseFolding = true))
        try {
            val snapshot = blocking {
                val pager = repo.pager(searchUri.toString(), pageSize = PAGE_SIZE)
                var s = pager.refresh()
                while (s.hasMore && s.files.size < SEARCH_LIMIT) s = pager.loadMore()
                s
            }
            val now = SystemClock.elapsedRealtime()
            snapshot.files.take(SEARCH_LIMIT).forEach { file ->
                val id = try {
                    DocumentId.of(root.accountId, file)
                } catch (_: FileNotFoundException) {
                    return@forEach
                }
                val cap = file.capability ?: snapshot.props.capability
                items.put(id.encoded, Entry(file, cap, now))
                DocumentRows.addFile(cursor, id.encoded, file, cap)
            }
        } catch (e: Exception) {
            cursor.extras = Bundle().apply {
                putString(DocumentsContract.EXTRA_ERROR, context!!.getString(R.string.documents_search_failed, describe(e)))
            }
        }
        return cursor
    }

    // ---- 打开 ----

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        if (id is DocumentId.Root) throw FileNotFoundException("Cannot open a folder")
        val account = account(id)
        val repo = files(id)
        return if (mode == "r") openRead(id, account, repo, signal) else openWrite(id, mode, repo, signal)
    }

    private fun openRead(id: DocumentId, account: Account, repo: FileRepository, signal: CancellationSignal?): ParcelFileDescriptor {
        // 读前取最新信息：大小必须准确（代理描述符按它判断 EOF）
        val file = fetch(id, signal).file
        if (file.isFolder) throw FileNotFoundException("Cannot open a folder")
        val storage = context!!.getSystemService(StorageManager::class.java)
        val thread = HandlerThread("cr-doc-read").apply { start() }
        val reader = RemoteFileReader(repo, file, account.siteUrl, context!!.cacheDir, TransferRuntime.http, thread)
        if (needsRealFile(file)) {
            // 音视频多由系统 mediaextractor 解析，它无权读取 appfuse 代理描述符（SELinux 拒绝，播放器一直转圈）：
            // 先完整下载到缓存，返回真实文件描述符；打开后即删除路径，描述符关闭时空间自动回收
            thread.quitSafely()
            signal?.throwIfCanceled()
            val local = guard("download") { reader.downloadWhole(MEDIA_DOWNLOAD_TIMEOUT_MS, signal.hook()) }
            return try {
                ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                local.delete()
            }
        }
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader, Handler(thread.looper))
        } catch (e: IOException) {
            thread.quitSafely()
            throw FileNotFoundException(e.message).apply { initCause(e) }
        }
    }

    /** 不超过 [MEDIA_LOCAL_COPY_MAX] 的音视频改走本地副本；更大的仍用代理描述符（自带解析器的第三方播放器可读） */
    private fun needsRealFile(file: FileItem): Boolean {
        val mime = DocumentRows.mimeOf(file)
        return (mime.startsWith("video/") || mime.startsWith("audio/")) && file.size <= MEDIA_LOCAL_COPY_MAX
    }

    /**
     * 写：内容先写到收件箱文件，描述符关闭时入队上传（已存在 → overwrite=true 生成新版本；空内容同样入队）。
     * `rw`/`wa` 等需要旧内容的模式先完整下载现有内容。交出描述符前把回传所需的关联写入 [PendingWrites] 日志。
     */
    private fun openWrite(id: DocumentId, mode: String, repo: FileRepository, signal: CancellationSignal?): ParcelFileDescriptor {
        val existing = when (id) {
            is DocumentId.File -> fetch(id, signal).file
            else -> guard("info") {
                try {
                    fetch(id, signal).file
                } catch (e: FileNotFoundException) {
                    // 旧版路径 ID 指向尚不存在的对象：按新建处理
                    if (e.cause is ApiException) null else throw e
                }
            }
        }
        if (existing?.isFolder == true) throw FileNotFoundException("Cannot write a folder")
        val target = existing?.let { DocumentId.ownUri(it.path) } ?: (id as DocumentId.Path).uri
        val parentUri = target.parent() ?: throw FileNotFoundException("No parent")
        val ctx = context!!
        val temp = UploadSources.newCameraFile(ctx, target.name())
        val keepContent = (mode.contains('r') || mode.contains('a')) && !mode.contains('t')
        val record = PendingWrite(
            id = UUID.randomUUID().toString(),
            accountId = id.accountId,
            documentId = id.encoded,
            parentUri = parentUri.toString(),
            name = target.name(),
            existingSize = existing?.size,
            temp = temp.absolutePath,
            process = PendingWrites.processToken,
        )
        val journal = PendingWrites.journal(ctx)
        try {
            guard("prepare") {
                if (keepContent && existing != null && existing.size > 0) {
                    downloadTo(repo, existing, temp, signal)
                } else {
                    temp.createNewFile()
                }
                journal.put(record)
            }
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.parseMode(mode), Handler(writeThread.looper)) { error ->
                onWriteClosed(record, error)
            }
        } catch (e: Exception) {
            journal.remove(record.id)
            UploadSources.discard(ctx, temp.absolutePath)
            if (e is FileNotFoundException) throw e
            throw FileNotFoundException("open: ${describe(e)}").apply { initCause(e) }
        }
    }

    /** 写入方关闭描述符：先持久标记“已提交”，再调度交接（[PendingWriteWorker]） */
    private fun onWriteClosed(record: PendingWrite, error: IOException?) {
        val ctx = context!!
        val journal = PendingWrites.journal(ctx)
        if (error != null) {
            // 写入方异常关闭（崩溃等）：内容可能不完整，不当作保存
            Log.w(TAG, "Writer of ${record.name} closed with error; discard", error)
            journal.remove(record.id)
            UploadSources.discard(ctx, record.temp)
            return
        }
        try {
            journal.put(record.copy(closed = true))
        } catch (e: IOException) {
            // 未能标记时保留“打开”记录：下次启动按未确认的写入建暂停任务
            Log.w(TAG, "Mark ${record.name} closed failed", e)
        }
        PendingWrites.schedule(ctx)
        items.remove(record.documentId)
        notifyDocument(record.documentId)
        scope.launch { awaitWriteBack(record) }
    }

    /** 等写回的上传任务结束，失效缓存并通知文档与所在目录 */
    private suspend fun awaitWriteBack(record: PendingWrite) {
        try {
            withTimeoutOrNull(WRITE_WATCH_MS) {
                CoreGraph.transferRepository().observeTasks(record.accountId)
                    .map { list -> list.firstOrNull { it.sourceRef == record.temp }?.state }
                    .first { it != null && (it.isTerminal || it == TransferState.FAILED || it == TransferState.PAUSED) }
            }
            items.remove(record.documentId)
            notifyDocument(record.documentId)
            CrUri.parseOrNull(record.parentUri)?.let { invalidateDir(record.accountId, it) }
            refreshCapacity(record.accountId, force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watch write-back of ${record.name} failed", e)
        }
    }

    /** 启动时处理上次进程留下的待提交写入 */
    private fun recoverPendingWrites() {
        if (!CoreGraph.isStarted) return
        val ctx = context!!
        scope.launch {
            if (PendingWrites.journal(ctx).all().isNotEmpty()) PendingWrites.schedule(ctx)
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        val repo = files(id)
        val entry = entry(id, signal)
        val file = entry.file
        if (!DocumentRows.thumbnailCandidate(file)) throw FileNotFoundException("No thumbnail")
        val dir = File(context!!.cacheDir, THUMB_DIR).apply { mkdirs() }
        val key = sha256("${id.encoded}|${file.updatedAt}|${file.size}")
        val target = File(dir, key)
        if (!target.exists()) {
            guard("thumb") {
                val url = blocking(signal) { repo.thumbUrl(file) } ?: throw FileNotFoundException("No thumbnail")
                val tmp = File(dir, "$key.part")
                fetchTo(absoluteUrl(id, url), tmp, signal, NETWORK_TIMEOUT_MS)
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
            trimThumbs(dir)
        }
        val pfd = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ---- 修改（总是按服务端最新位置操作，避免按过期路径改到别的对象） ----

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        awaitGraph()
        val parent = DocumentId.parse(parentDocumentId)
        val repo = files(parent)
        val dir = locate(parent, signal = null, fresh = true)
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val baseName = displayName.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }
            .joinToString("").trim().ifEmpty { "untitled" }
        var created: FileItem? = null
        var name = baseName
        for (attempt in 0..MAX_NAME_ATTEMPTS) {
            name = if (attempt == 0) baseName else numbered(baseName, attempt, isDir)
            try {
                created = guard("create") {
                    blocking {
                        if (isDir) repo.createFolder(dir.toString(), name) else repo.createFile(dir.toString(), name)
                    }
                }
                break
            } catch (e: FileNotFoundException) {
                // 重名（40004）换个名字再试，其它错误直接失败
                if ((e.cause as? ApiException)?.code != CODE_OBJECT_EXISTED) throw e
            }
        }
        val file = created ?: throw FileNotFoundException("Name conflict: $displayName")
        val id = documentIdOf(parent.accountId, file) ?: DocumentId.Path(parent.accountId, dir.join(name))
        items.put(id.encoded, Entry(file, file.capability ?: dirCapabilities[dirKey(parent.accountId, dir)], SystemClock.elapsedRealtime()))
        invalidateDir(parent.accountId, dir)
        return id.encoded
    }

    override fun deleteDocument(documentId: String) {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        if (id is DocumentId.Root) throw FileNotFoundException("Cannot delete root")
        val repo = files(id)
        val uri = locate(id, signal = null, fresh = true)
        guard("delete") { blocking { repo.delete(listOf(uri.toString())) } }
        items.remove(documentId)
        revokeDocumentPermission(documentId)
        notifyDocument(documentId)
        uri.parent()?.let { invalidateDir(id.accountId, it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        if (id is DocumentId.Root) throw FileNotFoundException("Cannot rename root")
        val repo = files(id)
        val uri = locate(id, signal = null, fresh = true)
        val renamed = guard("rename") { blocking { repo.rename(uri.toString(), displayName) } }
        val dir = uri.parent()!!
        val newId = documentIdOf(id.accountId, renamed) ?: DocumentId.Path(id.accountId, dir.join(displayName))
        items.remove(documentId)
        items.put(newId.encoded, Entry(renamed, renamed.capability ?: dirCapabilities[dirKey(id.accountId, dir)], SystemClock.elapsedRealtime()))
        notifyDocument(documentId)
        invalidateDir(id.accountId, dir)
        // 文件 ID 不随改名变化：返回 null 表示 ID 未变，系统保留已有授权
        return newId.encoded.takeIf { it != documentId }
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        awaitGraph()
        val source = DocumentId.parse(sourceDocumentId)
        val target = DocumentId.parse(targetParentDocumentId)
        requireSameAccount(source, target)
        val repo = files(source)
        val from = locate(source, signal = null, fresh = true)
        val into = locate(target, signal = null, fresh = true)
        guard("move") { blocking { repo.move(listOf(from.toString()), into.toString()) } }
        items.remove(sourceDocumentId)
        notifyDocument(sourceDocumentId)
        from.parent()?.let { invalidateDir(source.accountId, it) }
        invalidateDir(source.accountId, into)
        return if (source is DocumentId.File) sourceDocumentId else lookupId(source.accountId, repo, into.join(from.name())).encoded
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        awaitGraph()
        val source = DocumentId.parse(sourceDocumentId)
        val target = DocumentId.parse(targetParentDocumentId)
        requireSameAccount(source, target)
        val repo = files(source)
        val from = locate(source, signal = null, fresh = true)
        val into = locate(target, signal = null, fresh = true)
        guard("copy") { blocking { repo.copy(listOf(from.toString()), into.toString()) } }
        invalidateDir(source.accountId, into)
        return lookupId(source.accountId, repo, into.join(from.name())).encoded
    }

    /** 新位置上的对象 ID（取不到时退回路径 ID） */
    private fun lookupId(accountId: String, repo: FileRepository, uri: CrUri): DocumentId = try {
        val file = blocking { repo.info(uri.toString()) }
        documentIdOf(accountId, file)?.also { items.put(it.encoded, Entry(file, file.capability, SystemClock.elapsedRealtime())) }
            ?: DocumentId.Path(accountId, uri)
    } catch (e: Exception) {
        DocumentId.Path(accountId, uri)
    }

    // ---- 位置解析 ----

    /**
     * 文档的当前位置。[fresh] 为 true 时文件 ID 总是向服务端确认（修改类操作）；
     * 否则优先用内存中已知的位置。
     */
    private fun locate(id: DocumentId, signal: CancellationSignal?, fresh: Boolean): CrUri = when (id) {
        is DocumentId.Root -> CrUri.my()
        is DocumentId.Path -> id.uri
        is DocumentId.File -> (if (fresh) null else items.get(id.encoded)?.let { uriOf(it.file) }) ?: uriOf(fetch(id, signal).file)
            ?: throw FileNotFoundException("No location: $id")
    }

    /** 同 [locate]，但只信任 [RESOLVE_FRESH_MS] 内从服务端确认过的位置 */
    private fun locateRecent(id: DocumentId): CrUri {
        if (id is DocumentId.File) {
            val entry = items.get(id.encoded)
            if (entry != null && entry.resolvedAt != 0L && SystemClock.elapsedRealtime() - entry.resolvedAt <= RESOLVE_FRESH_MS) {
                uriOf(entry.file)?.let { return it }
            }
        }
        return locate(id, signal = null, fresh = true)
    }

    /** 按过期位置操作失败后重新解析；位置确有变化时返回新位置 */
    private fun relocated(id: DocumentId, stale: CrUri): CrUri? {
        if (id !is DocumentId.File) return null
        return try {
            locate(id, signal = null, fresh = true).takeIf { it != stale }
        } catch (e: Exception) {
            null
        }
    }

    /** 取对象信息：先查内存，再向服务端查询 */
    private fun entry(id: DocumentId, signal: CancellationSignal?): Entry = items.get(id.encoded) ?: fetch(id, signal)

    /** 向服务端查询对象（文件 ID 用 `GET /file/info?id=`），写入缓存。不在“我的文件”中（如已进回收站）视为不存在 */
    private fun fetch(id: DocumentId, signal: CancellationSignal?): Entry {
        val repo = files(id)
        var resolvedAt = SystemClock.elapsedRealtime()
        val file = guard("info") {
            blocking(signal) {
                when (id) {
                    is DocumentId.File -> try {
                        repo.infoById(id.fileId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        throw e
                    } catch (e: Exception) {
                        // 离线：在本地目录缓存里找这个 ID 最近一次出现的位置
                        resolvedAt = 0L
                        offlineLookup(repo, id.fileId) ?: throw e
                    }
                    is DocumentId.Path -> repo.info(id.uri.toString())
                    is DocumentId.Root -> throw FileNotFoundException("Root has no info")
                }
            }
        }
        val uri = DocumentId.ownUri(file.path)
        val dirCapability = uri.parent()?.let { dirCapabilities[dirKey(id.accountId, it)] }
        return Entry(file, dirCapability, resolvedAt).also { items.put(id.encoded, it) }
    }

    private suspend fun offlineLookup(repo: FileRepository, fileId: String): FileItem? =
        repo.recentListings(OFFLINE_LOOKUP_LISTINGS).firstNotNullOfOrNull { page ->
            page.files.firstOrNull { it.id == fileId } ?: page.parent?.takeIf { it.id == fileId }
        }

    private fun uriOf(file: FileItem): CrUri? = try {
        DocumentId.ownUri(file.path)
    } catch (_: FileNotFoundException) {
        null
    }

    private fun documentIdOf(accountId: String, file: FileItem): DocumentId? = try {
        DocumentId.of(accountId, file)
    } catch (_: FileNotFoundException) {
        null
    }

    private fun dirKey(accountId: String, dir: CrUri) = "$accountId|$dir"

    // ---- 列目录缓存 ----

    private fun addListing(
        cursor: MatrixCursor,
        parent: DocumentId,
        dir: CrUri,
        children: List<FileItem>,
        capability: BoolSet?,
        self: FileItem?,
        resolvedAt: Long,
    ) {
        capability?.let { dirCapabilities[dirKey(parent.accountId, dir)] = it }
        if (self != null && self.path.isNotEmpty()) {
            val upCapability = dir.parent()?.let { dirCapabilities[dirKey(parent.accountId, it)] }
            val entry = Entry(self.copy(capability = self.capability ?: capability), upCapability, resolvedAt)
            items.put(parent.encoded, entry)
            documentIdOf(parent.accountId, self)?.let { items.put(it.encoded, entry) }
        }
        for (file in children) {
            val id = documentIdOf(parent.accountId, file) ?: continue
            items.put(id.encoded, Entry(file, capability, resolvedAt))
            DocumentRows.addFile(cursor, id.encoded, file, capability)
        }
    }

    /** 后台拉取全部分页写入缓存，结束后通知系统重新查询；同一目录同时只跑一个 */
    private fun loadAll(parent: DocumentId, dir: CrUri, state: ListingState, notifyUri: Uri, pager: ListingPager?) {
        if (!state.loading.compareAndSet(false, true)) return
        scope.launch {
            try {
                try {
                    (pager ?: files(parent).pager(dir.toString(), pageSize = PAGE_SIZE)).loadAll(MAX_PAGES)
                } catch (e: ApiException) {
                    // 目录已被改名/移动：按文件 ID 取新位置重列
                    val moved = relocated(parent, dir) ?: throw e
                    state.uri = moved
                    files(parent).pager(moved.toString(), pageSize = PAGE_SIZE).loadAll(MAX_PAGES)
                }
                state.error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "List $dir failed", e)
                state.error = describe(e)
            } finally {
                state.loadedAt = SystemClock.elapsedRealtime()
                state.loading.set(false)
                resolver().notifyChange(notifyUri, null, false)
            }
        }
    }

    /**
     * 目录内容已变：清除新鲜标记并通知列过该目录的全部文档 ID（新旧 ID 都可能被外部应用观察），
     * 重新查询时会先给旧缓存，再后台刷新。
     */
    private fun invalidateDir(accountId: String, dir: CrUri) {
        val ids = LinkedHashSet<String>()
        for ((key, state) in listings) {
            if (key.startsWith("$accountId|") && state.uri == dir) {
                state.loadedAt = 0L
                ids += key
            }
        }
        if (dir.isRoot()) ids += DocumentId.Root(accountId).encoded
        ids += DocumentId.Path(accountId, dir).encoded
        for ((key, entry) in items.snapshot()) {
            if (key.startsWith("$accountId|") && entry.file.isFolder && uriOf(entry.file) == dir) ids += key
        }
        ids.forEach { resolver().notifyChange(DocumentsContract.buildChildDocumentsUri(authority, it), null, false) }
    }

    private fun notifyDocument(documentId: String) {
        resolver().notifyChange(documentUri(documentId), null, false)
    }

    private fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUri(authority, documentId)

    // ---- 账户 ----

    private fun watchAccounts() {
        if (!CoreGraph.isStarted) return
        scope.launch {
            CoreGraph.accounts().accounts
                .map { list -> list.filter { !it.sessionExpired }.map { it.id to rootTitle(it) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { active ->
                    val ids = active.map { it.first }.toSet()
                    listings.keys.removeAll { key -> ids.none { key.startsWith("$it|") } }
                    dirCapabilities.keys.removeAll { key -> ids.none { key.startsWith("$it|") } }
                    capacityRequested.retainAll(ids)
                    resolver().notifyChange(rootsUri(), null, false)
                }
        }
    }

    private fun activeAccounts(): List<Account> = CoreGraph.accounts().accounts.value.filter { !it.sessionExpired }

    private fun account(id: DocumentId): Account =
        CoreGraph.accounts().account(id.accountId)?.takeIf { !it.sessionExpired }
            ?: throw FileNotFoundException("Account unavailable: ${id.accountId}")

    private fun files(id: DocumentId): FileRepository {
        account(id)
        return CoreGraph.files(id.accountId)
    }

    private fun rootTitle(account: Account): String =
        account.nickname.ifBlank { account.email.ifBlank { context!!.getString(R.string.app_name) } }

    private fun refreshCapacity(accountId: String, force: Boolean = false) {
        if (!force && !capacityRequested.add(accountId)) return
        scope.launch {
            try {
                CoreGraph.userSettings(accountId).refreshCapacity()
                resolver().notifyChange(rootsUri(), null, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Capacity of $accountId failed", e)
            }
        }
    }

    private fun requireSameAccount(a: DocumentId, b: DocumentId) {
        if (a.accountId != b.accountId) throw IllegalArgumentException("Cross-account operation is not supported")
    }

    // ---- 工具 ----

    /** 下载现有内容到 [target]（`rw`/`wa` 打开），调用方取消时中断请求 */
    private fun downloadTo(repo: FileRepository, file: FileItem, target: File, signal: CancellationSignal?) {
        val url = blocking(signal) {
            repo.fileUrl(file, download = true).urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
                ?: throw IOException("empty url")
        }
        val account = CoreGraph.accounts().account(repo.accountId) ?: throw IOException("account gone")
        fetchTo(if (url.startsWith("/")) account.siteUrl.trimEnd('/') + url else url, target, signal, TRANSFER_TIMEOUT_MS)
    }

    private fun absoluteUrl(id: DocumentId, url: String): String =
        if (url.startsWith("/")) account(id).siteUrl.trimEnd('/') + url else url

    private fun fetchTo(url: String, target: File, signal: CancellationSignal?, timeoutMs: Long) {
        CancellableHttp.execute(TransferRuntime.http, Request.Builder().url(url).build(), timeoutMs, signal.hook()) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            CancellableHttp.writeBody(response, target)
        }
    }

    private fun CancellationSignal?.hook(): CancellableHttp.CancelHook? = this?.let { signal ->
        CancellableHttp.CancelHook { action ->
            signal.setOnCancelListener(action?.let { run -> CancellationSignal.OnCancelListener { run() } })
        }
    }

    private fun trimThumbs(dir: File) {
        val all = dir.listFiles() ?: return
        if (all.size <= MAX_THUMBS) return
        all.sortedBy { it.lastModified() }.take(all.size - MAX_THUMBS).forEach { it.delete() }
    }

    /** Binder 线程上的挂起调用：带超时，并在系统取消时中止 */
    private fun <T> blocking(signal: CancellationSignal? = null, timeoutMs: Long = NETWORK_TIMEOUT_MS, block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) {
            val job = coroutineContext.job
            signal?.setOnCancelListener { job.cancel() }
            try {
                withTimeout(timeoutMs) { block() }
            } finally {
                signal?.setOnCancelListener(null)
            }
        }

    /** 把失败统一转成 [FileNotFoundException]（保留原因），系统据此提示操作失败 */
    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        throw FileNotFoundException("$what: ${describe(e)}").apply { initCause(e) }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> e.serverMsg.ifBlank { "code ${e.code}" }
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun resolver() = context!!.contentResolver

    private fun rootsUri(): Uri = DocumentsContract.buildRootsUri(authority)

    private val writeThread: HandlerThread by lazy { HandlerThread("cr-doc-write").apply { start() } }

    companion object {
        private const val TAG = "CloudreveDocuments"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 200
        private const val SEARCH_LIMIT = 100
        private const val ITEM_CACHE_SIZE = 4000
        private const val MAX_THUMBS = 300
        private const val MAX_NAME_ATTEMPTS = 20
        private const val CODE_OBJECT_EXISTED = 40004
        private const val FRESH_MS = 30_000L
        private const val RESOLVE_FRESH_MS = 30_000L
        private const val NETWORK_TIMEOUT_MS = 30_000L
        private const val TRANSFER_TIMEOUT_MS = 10 * 60_000L
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        private const val WRITE_WATCH_MS = 6 * 60 * 60_000L
        private const val OFFLINE_LOOKUP_LISTINGS = 500
        private const val GRAPH_WAIT_MS = 10_000L
        private const val THUMB_DIR = "documents-thumbs"
        private const val MEDIA_LOCAL_COPY_MAX = 512L * 1024 * 1024

        private val DEFAULT_ROOT_PROJECTION = buildList {
            add(Root.COLUMN_ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID)
            add(Root.COLUMN_TITLE)
            add(Root.COLUMN_SUMMARY)
            add(Root.COLUMN_ICON)
            add(Root.COLUMN_FLAGS)
            add(Root.COLUMN_AVAILABLE_BYTES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Root.COLUMN_CAPACITY_BYTES)
        }.toTypedArray()

        /** 依赖图由 Application.onCreate 启动；提供方可能先被调用，短暂等待 */
        private fun awaitGraph() {
            val deadline = SystemClock.uptimeMillis() + GRAPH_WAIT_MS
            while (!CoreGraph.isStarted) {
                if (SystemClock.uptimeMillis() > deadline) throw IllegalStateException("Core graph not started")
                SystemClock.sleep(20)
            }
        }

        /** `name (n).ext`；目录不拆扩展名 */
        private fun numbered(name: String, n: Int, isDir: Boolean): String {
            val dot = name.lastIndexOf('.')
            return if (isDir || dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
