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
import okhttp3.Request
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.auth.Account
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.SearchParams
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.transfer.TransferRuntime
import plus.ling.opencloudreve.transfer.UploadSources
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统文件集成（Storage Access Framework）：每个有效账户一个根，根目录为 `cloudreve://my`。
 *
 * - 列目录先返回 `ListingCache` 并带 `EXTRA_LOADING`，后台拉取全部分页写回缓存后 `notifyChange`；无缓存时同步拉第一页；
 * - 读：代理文件描述符按 Range 请求签名地址（[RemoteFileReader]）；写：写入临时文件，关闭时交给传输引擎上传；
 * - Binder 线程上的网络调用都有超时并响应 [CancellationSignal]，不会无限阻塞。
 *
 * 注意：ContentProvider 早于 `Application.onCreate` 创建并发布，所以每个入口先 [awaitGraph]。
 */
class CloudreveDocumentsProvider : DocumentsProvider() {
    private lateinit var authority: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 列目录/新建得到的对象（含所在目录能力位），供 queryDocument/打开/缩略图免请求查询 */
    private val items = LruCache<String, Entry>(ITEM_CACHE_SIZE)
    private val listings = ConcurrentHashMap<String, ListingState>()
    private val capacityRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class Entry(val file: FileItem, val dirCapability: BoolSet?)

    private class ListingState {
        val loading = AtomicBoolean(false)
        @Volatile var loadedAt = 0L
        @Volatile var error: String? = null
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        authority = info.authority
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean {
        // 主线程消息在 Application.onCreate（依赖图启动）之后才执行
        Handler(Looper.getMainLooper()).post { watchAccounts() }
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
        if (id.isRoot) {
            val capability = items.get(documentId)?.file?.capability
            DocumentRows.addRoot(cursor, id, rootTitle(account), capability)
        } else {
            val entry = entry(id, signal = null)
            DocumentRows.addFile(cursor, id, entry.file, entry.dirCapability)
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

        val cached = try {
            blocking { repo.cachedListing(parent.uri.toString()) }
        } catch (e: Exception) {
            null
        }
        if (cached != null) {
            addListing(cursor, parent, cached.files, cached.props.capability, cached.parent)
            if (SystemClock.elapsedRealtime() - state.loadedAt > FRESH_MS || state.loadedAt == 0L) {
                loadAll(parent, state, notifyUri, pager = null)
            }
        } else {
            // 无缓存：同步取第一页，其余分页后台继续
            val pager = repo.pager(parent.uri.toString(), pageSize = PAGE_SIZE)
            try {
                val snapshot = blocking { pager.refresh() }
                addListing(cursor, parent, snapshot.files, snapshot.props.capability, snapshot.parent)
                if (snapshot.hasMore) {
                    loadAll(parent, state, notifyUri, pager)
                } else {
                    state.loadedAt = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                state.error = describe(e)
            }
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

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        DocumentId.parse(parentDocumentId).isAncestorOf(DocumentId.parse(documentId))
    } catch (_: FileNotFoundException) {
        false
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        awaitGraph()
        val root = DocumentId.root(rootId)
        val repo = files(root)
        val cursor = MatrixCursor(projection ?: DocumentRows.DEFAULT_DOCUMENT_PROJECTION)
        val keyword = query.trim()
        if (keyword.isEmpty()) return cursor
        val searchUri = root.uri.withSearch(SearchParams(names = listOf(keyword), caseFolding = true))
        try {
            val snapshot = blocking {
                val pager = repo.pager(searchUri.toString(), pageSize = PAGE_SIZE)
                var s = pager.refresh()
                while (s.hasMore && s.files.size < SEARCH_LIMIT) s = pager.loadMore()
                s
            }
            snapshot.files.take(SEARCH_LIMIT).forEach { file ->
                val id = DocumentId.of(root.accountId, file.path)
                val cap = file.capability ?: snapshot.props.capability
                items.put(id.encoded, Entry(file, cap))
                DocumentRows.addFile(cursor, id, file, cap)
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
        if (id.isRoot) throw FileNotFoundException("Cannot open a folder")
        val account = account(id)
        val repo = files(id)
        return if (mode == "r") openRead(id, account, repo, signal) else openWrite(id, mode, repo, signal)
    }

    private fun openRead(id: DocumentId, account: Account, repo: FileRepository, signal: CancellationSignal?): ParcelFileDescriptor {
        // 读前取最新信息：大小必须准确（代理描述符按它判断 EOF）
        val file = guard("info") { blocking(signal) { repo.info(id.uri.toString()) } }
        if (file.isFolder) throw FileNotFoundException("Cannot open a folder")
        val storage = context!!.getSystemService(StorageManager::class.java)
        val thread = HandlerThread("cr-doc-read").apply { start() }
        val reader = RemoteFileReader(repo, file, account.siteUrl, context!!.cacheDir, TransferRuntime.http, thread)
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader, Handler(thread.looper))
        } catch (e: IOException) {
            thread.quitSafely()
            throw FileNotFoundException(e.message).apply { initCause(e) }
        }
    }

    /**
     * 写：内容先写到收件箱临时文件，描述符关闭时入队上传（已存在 → overwrite=true 生成新版本）。
     * `rw`/`wa` 等需要旧内容的模式先完整下载现有内容。
     */
    private fun openWrite(id: DocumentId, mode: String, repo: FileRepository, signal: CancellationSignal?): ParcelFileDescriptor {
        val parentId = id.parent() ?: throw FileNotFoundException("No parent")
        val existing = guard("info") {
            try {
                blocking(signal) { repo.info(id.uri.toString()) }
            } catch (_: ApiException) {
                null
            }
        }
        if (existing?.isFolder == true) throw FileNotFoundException("Cannot write a folder")
        val ctx = context!!
        val temp = UploadSources.newCameraFile(ctx, id.name)
        val keepContent = (mode.contains('r') || mode.contains('a')) && !mode.contains('t')
        guard("prepare") {
            if (keepContent && existing != null && existing.size > 0) {
                blocking(signal, TRANSFER_TIMEOUT_MS) { download(repo, existing, temp) }
            } else {
                temp.createNewFile()
            }
        }
        val ioThread = writeThread
        return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.parseMode(mode), Handler(ioThread.looper)) { error ->
            if (error != null) {
                Log.w(TAG, "Writer of ${id.uri} closed with error; discard", error)
                UploadSources.discard(ctx, temp.absolutePath)
            } else {
                scope.launch { uploadWritten(id, parentId, temp, existing) }
            }
        }
    }

    private suspend fun uploadWritten(id: DocumentId, parentId: DocumentId, temp: File, existing: FileItem?) {
        val ctx = context!!
        val repo = files(id)
        try {
            if (temp.length() == 0L) {
                // 空内容：已存在的空文件无需上传；新文件直接创建空文件
                UploadSources.discard(ctx, temp.absolutePath)
                if (existing == null) repo.createFile(parentId.uri.toString(), id.name)
                else if (existing.size == 0L) return
                else repo.putContent(id.uri.toString(), ByteArray(0), existing.primaryEntity)
                invalidate(parentId)
                return
            }
            val source = UploadSource(temp.absolutePath, id.name, temp.length(), temp.lastModified(), DocumentRows.mimeOfName(id.name))
            val item = CoreGraph.transferManager()
                .enqueueUploads(id.accountId, listOf(source), parentId.uri.toString(), policy = null, overwrite = existing != null)
                .firstOrNull() ?: return
            // 未通过预校验的任务不会调度，执行器也就不会清理源文件
            if (item.state == TransferState.FAILED) UploadSources.discard(ctx, temp.absolutePath)
            invalidate(parentId)
            CoreGraph.transferRepository().observeTasks(id.accountId)
                .map { list -> list.firstOrNull { it.id == item.id }?.state }
                .first { it == null || it.isTerminal || it == TransferState.FAILED }
            invalidate(parentId)
            refreshCapacity(id.accountId, force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Upload ${id.uri} failed", e)
            UploadSources.discard(ctx, temp.absolutePath)
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
                blocking(signal) {
                    val url = repo.thumbUrl(file) ?: throw FileNotFoundException("No thumbnail")
                    val tmp = File(dir, "$key.part")
                    fetchTo(absoluteUrl(id, url), tmp)
                    if (!tmp.renameTo(target)) throw IOException("rename failed")
                }
            }
            trimThumbs(dir)
        }
        val pfd = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ---- 修改 ----

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        awaitGraph()
        val parent = DocumentId.parse(parentDocumentId)
        val repo = files(parent)
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
                        if (isDir) repo.createFolder(parent.uri.toString(), name) else repo.createFile(parent.uri.toString(), name)
                    }
                }
                break
            } catch (e: FileNotFoundException) {
                // 重名（40004）换个名字再试，其它错误直接失败
                if ((e.cause as? ApiException)?.code != CODE_OBJECT_EXISTED) throw e
            }
        }
        val file = created ?: throw FileNotFoundException("Name conflict: $displayName")
        val id = if (file.path.isNotEmpty()) DocumentId.of(parent.accountId, file.path) else parent.child(name)
        items.put(id.encoded, Entry(file, file.capability ?: items.get(parentDocumentId)?.file?.capability))
        invalidate(parent)
        return id.encoded
    }

    override fun deleteDocument(documentId: String) {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        if (id.isRoot) throw FileNotFoundException("Cannot delete root")
        val repo = files(id)
        guard("delete") { blocking { repo.delete(listOf(id.uri.toString())) } }
        items.remove(documentId)
        revokeDocumentPermission(documentId)
        id.parent()?.let { invalidate(it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        awaitGraph()
        val id = DocumentId.parse(documentId)
        if (id.isRoot) throw FileNotFoundException("Cannot rename root")
        val repo = files(id)
        val renamed = guard("rename") { blocking { repo.rename(id.uri.toString(), displayName) } }
        val parent = id.parent()!!
        val newId = if (renamed.path.isNotEmpty()) DocumentId.of(id.accountId, renamed.path) else parent.child(displayName)
        items.remove(documentId)
        items.put(newId.encoded, Entry(renamed, renamed.capability ?: items.get(parent.encoded)?.file?.capability))
        invalidate(parent)
        return newId.encoded
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        awaitGraph()
        val source = DocumentId.parse(sourceDocumentId)
        val target = DocumentId.parse(targetParentDocumentId)
        requireSameAccount(source, target)
        val repo = files(source)
        guard("move") { blocking { repo.move(listOf(source.uri.toString()), target.uri.toString()) } }
        items.remove(sourceDocumentId)
        invalidate(DocumentId.parse(sourceParentDocumentId))
        invalidate(target)
        return target.child(source.name).encoded
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        awaitGraph()
        val source = DocumentId.parse(sourceDocumentId)
        val target = DocumentId.parse(targetParentDocumentId)
        requireSameAccount(source, target)
        val repo = files(source)
        guard("copy") { blocking { repo.copy(listOf(source.uri.toString()), target.uri.toString()) } }
        invalidate(target)
        return target.child(source.name).encoded
    }

    // ---- 列目录缓存 ----

    private fun addListing(cursor: MatrixCursor, parent: DocumentId, children: List<FileItem>, capability: BoolSet?, self: FileItem?) {
        if (self != null && self.path.isNotEmpty()) {
            items.put(parent.encoded, Entry(self.copy(capability = self.capability ?: capability), items.get(parent.encoded)?.dirCapability))
        }
        for (file in children) {
            val id = try {
                DocumentId.of(parent.accountId, file.path)
            } catch (_: FileNotFoundException) {
                continue
            }
            items.put(id.encoded, Entry(file, capability))
            DocumentRows.addFile(cursor, id, file, capability)
        }
    }

    /** 后台拉取全部分页写入缓存，结束后通知系统重新查询；同一目录同时只跑一个 */
    private fun loadAll(parent: DocumentId, state: ListingState, notifyUri: Uri, pager: plus.ling.opencloudreve.core.files.ListingPager?) {
        if (!state.loading.compareAndSet(false, true)) return
        scope.launch {
            try {
                (pager ?: files(parent).pager(parent.uri.toString(), pageSize = PAGE_SIZE)).loadAll(MAX_PAGES)
                state.error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "List ${parent.uri} failed", e)
                state.error = describe(e)
            } finally {
                state.loadedAt = SystemClock.elapsedRealtime()
                state.loading.set(false)
                resolver().notifyChange(notifyUri, null, false)
            }
        }
    }

    /** 目录内容已变：清除新鲜标记并通知（重新查询时会先给旧缓存，再后台刷新） */
    private fun invalidate(dir: DocumentId) {
        listings[dir.encoded]?.loadedAt = 0L
        resolver().notifyChange(DocumentsContract.buildChildDocumentsUri(authority, dir.encoded), null, false)
    }

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

    /** 取对象信息：先查内存，再 `GET /file/info` */
    private fun entry(id: DocumentId, signal: CancellationSignal?): Entry {
        items.get(id.encoded)?.let { return it }
        val repo = files(id)
        val file = guard("info") { blocking(signal) { repo.info(id.uri.toString()) } }
        val dirCapability = id.parent()?.let { items.get(it.encoded)?.file?.capability }
        return Entry(file, dirCapability).also { items.put(id.encoded, it) }
    }

    private suspend fun download(repo: FileRepository, file: FileItem, target: File) {
        val url = repo.fileUrl(file, download = true).urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
            ?: throw IOException("empty url")
        val account = CoreGraph.accounts().account(repo.accountId) ?: throw IOException("account gone")
        fetchTo(if (url.startsWith("/")) account.siteUrl.trimEnd('/') + url else url, target)
    }

    private fun absoluteUrl(id: DocumentId, url: String): String =
        if (url.startsWith("/")) account(id).siteUrl.trimEnd('/') + url else url

    private fun fetchTo(url: String, target: File) {
        TransferRuntime.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
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
        private const val NETWORK_TIMEOUT_MS = 30_000L
        private const val TRANSFER_TIMEOUT_MS = 10 * 60_000L
        private const val GRAPH_WAIT_MS = 10_000L
        private const val THUMB_DIR = "documents-thumbs"

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
