package plus.ling.opencloudreve.core.presentation.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.auth.SiteUrl
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.ListPage
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.share.ShareRepository

/** 分享内面包屑 */
data class ShareCrumb(val name: String, val uri: String)

data class ShareVisitState(
    /** 原始输入 */
    val link: String,
    /** 解析出的分享 id；链接无效为 null */
    val shareId: String? = null,
    /** 分享信息（未解锁时为精简信息） */
    val share: Share? = null,
    val loading: Boolean = false,
    /** 整页错误（链接无效 Validation、不存在/过期 NotFound/LinkExpired、无权访问 Permission） */
    val error: UiError? = null,
    /** 需要输入密码 */
    val needsPassword: Boolean = false,
    /** 上次提交的密码错误（40069） */
    val passwordIncorrect: Boolean = false,
    val submittingPassword: Boolean = false,
    /** 当前浏览目录（带密码的分享 URI）；解锁前为 null */
    val currentUri: String? = null,
    val crumbs: List<ShareCrumb> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val loadingList: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** 单文件分享：平台直接进入该文件预览 */
    val singleFile: FileItem? = null,
    /** 当前目录的 README（`show_readme` 且为目录分享；README.md 优先于 README.txt） */
    val readme: FileItem? = null,
    /** 当前用户就是分享者 */
    val isOwner: Boolean = false,
    val saving: Boolean = false,
    val contextHint: String? = null,
    /** 链接站点与当前账户站点不同（平台可提示切换账户） */
    val siteMismatch: Boolean = false,
) {
    val unlocked: Boolean get() = share?.unlocked == true && currentUri != null
    val isRoot: Boolean get() = crumbs.size <= 1
    val canGoUp: Boolean get() = !isRoot
    /** 可保存到我的文件（已解锁、不是自己的分享） */
    val canSave: Boolean get() = unlocked && !isOwner && !saving && share?.owner != null && !share.name.isNullOrEmpty()

    /** 快捷方式指向的 URI：单文件分享为该文件 path，否则为分享根（带密码） */
    val shortcutTarget: String? get() = singleFile?.path ?: crumbs.firstOrNull()?.uri
}

sealed interface ShareVisitIntent {
    data class SubmitPassword(val password: String) : ShareVisitIntent

    /** 进入子目录（目录项的 path） */
    data class Open(val uri: String) : ShareVisitIntent
    data object Up : ShareVisitIntent
    data object Refresh : ShareVisitIntent
    data object LoadMore : ShareVisitIntent
    /**
     * 保存到我的文件：在 [dstUri] 目录下创建以分享名命名、指向分享的快捷方式（与网页端“保存分享为快捷方式”一致；
     * 服务端不支持从分享复制文件）。重名返回 40004。
     */
    data class SaveToMyFiles(val dstUri: String) : ShareVisitIntent

    /** 关闭 README 提示 */
    data object DismissReadme : ShareVisitIntent
}

sealed interface ShareVisitEffect {
    /** 已在 [dstUri] 下创建快捷方式 [shortcut] */
    data class Saved(val dstUri: String, val shortcut: FileItem) : ShareVisitEffect

    /** 非整页错误（保存失败：40004 同名已存在 → Conflict；加载更多失败等） */
    data class Failed(val error: UiError) : ShareVisitEffect
}

/**
 * 打开分享链接：解析链接 → `GET /share/info/:id?count_views=true` 显示头部 → 需要密码时输入（40069 重试）
 * → `GET /file?uri=cloudreve://<id>[:<pw>]@share` 浏览；单文件分享直接给出 [ShareVisitState.singleFile]；
 * 目录分享按 README.md → README.txt 查找说明文件；“保存到我的文件”在自己的目录创建分享快捷方式。
 */
class ShareVisitViewModel(
    val accountId: String,
    link: String,
    accountSiteUrl: String?,
    private val shares: ShareRepository,
    private val files: FileRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<ShareVisitState, ShareVisitIntent, ShareVisitEffect>(ShareVisitState(link = link)) {

    private val parsed: ShareLink? = ShareLinkParser.parse(link)
    private var password: String? = parsed?.password
    private var nextToken: String? = null
    private var nextPage: Int? = null
    private var listJob: Job? = null

    init {
        if (parsed == null) {
            updateState { it.copy(error = UiError(ErrorKind.Validation)) }
        } else {
            val mismatch = parsed.siteUrl != null && accountSiteUrl != null &&
                SiteUrl.hostOf(parsed.siteUrl) != SiteUrl.hostOf(accountSiteUrl)
            updateState { it.copy(shareId = parsed.id, siteMismatch = mismatch) }
            openShare(countViews = true, initialPath = parsed.path)
        }
    }

    override fun on(intent: ShareVisitIntent) {
        when (intent) {
            is ShareVisitIntent.SubmitPassword -> submitPassword(intent.password)
            is ShareVisitIntent.Open -> navigate(withPassword(intent.uri))
            ShareVisitIntent.Up -> {
                val crumbs = currentState.crumbs
                if (crumbs.size > 1) navigate(crumbs[crumbs.size - 2].uri)
            }
            ShareVisitIntent.Refresh -> {
                val uri = currentState.currentUri
                if (uri != null) navigate(uri) else if (parsed != null) openShare(countViews = false, initialPath = parsed.path)
            }
            ShareVisitIntent.LoadMore -> loadMore()
            is ShareVisitIntent.SaveToMyFiles -> save(intent.dstUri)
            ShareVisitIntent.DismissReadme -> updateState { it.copy(readme = null) }
        }
    }

    private fun openShare(countViews: Boolean, initialPath: List<String>) {
        val id = parsed?.id ?: return
        scope.launch {
            updateState { it.copy(loading = true, error = null) }
            try {
                val share = shares.info(id, password, countViews = countViews)
                handleInfo(share, initialPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, submittingPassword = false, error = e.toUiError()) }
            }
        }
    }

    private fun submitPassword(input: String) {
        val id = parsed?.id ?: return
        val pw = input.trim()
        if (pw.isEmpty() || currentState.submittingPassword) return
        scope.launch {
            updateState { it.copy(submittingPassword = true) }
            try {
                password = pw
                handleInfo(shares.info(id, pw), parsed.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(submittingPassword = false) }
                sendEffect(ShareVisitEffect.Failed(e.toUiError()))
            }
        }
    }

    private suspend fun handleInfo(share: Share, initialPath: List<String>) {
        if (share.expired) {
            updateState { it.copy(share = share, loading = false, submittingPassword = false, error = UiError(ErrorKind.LinkExpired)) }
            return
        }
        if (!share.unlocked) {
            val attempted = password != null
            password = null
            updateState {
                it.copy(
                    share = share,
                    loading = false,
                    submittingPassword = false,
                    needsPassword = true,
                    passwordIncorrect = attempted,
                )
            }
            return
        }
        // 所有者或已输入正确密码时服务端会回传密码
        password = share.password?.takeIf { it.isNotEmpty() } ?: password
        val root = CrUri.share(share.id, password)
        updateState {
            it.copy(share = share, loading = false, submittingPassword = false, needsPassword = false, passwordIncorrect = false)
        }
        resolveOwner()
        navigate(root.joinAll(initialPath).toString())
    }

    private suspend fun resolveOwner() {
        val ownerId = currentState.share?.owner?.id ?: return
        val me = userSettings.currentUserOrNull()
        updateState { it.copy(isOwner = me != null && me.id == ownerId) }
    }

    /** 分享内的 path 可能不带密码：补上已知密码 */
    private fun withPassword(uri: String): String {
        val cr = CrUri.parseOrNull(uri) ?: return uri
        return if (cr.fs == CrUri.FS_SHARE && cr.password == null && password != null) cr.withPassword(password).toString() else uri
    }

    private fun crumbsFor(uri: CrUri): List<ShareCrumb> {
        val root = uri.root()
        val rootName = currentState.share?.name.orEmpty()
        val list = arrayListOf(ShareCrumb(rootName, root.toString()))
        uri.segments.forEachIndexed { i, seg ->
            list += ShareCrumb(seg, root.joinAll(uri.segments.subList(0, i + 1)).toString())
        }
        return list
    }

    private fun navigate(uri: String) {
        val cr = CrUri.parseOrNull(uri) ?: return
        listJob?.cancel()
        listJob = scope.launch {
            updateState {
                it.copy(
                    currentUri = uri,
                    crumbs = crumbsFor(cr),
                    files = if (it.currentUri == uri) it.files else emptyList(),
                    loadingList = true,
                    hasMore = false,
                    readme = null,
                    singleFile = null,
                )
            }
            try {
                val page = files.listPage(uri)
                applyPage(page, append = false)
                detectReadme(cr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == ErrorCode.INCORRECT_PASSWORD) {
                    password = null
                    updateState { it.copy(loadingList = false, currentUri = null, needsPassword = true, passwordIncorrect = true) }
                } else {
                    failList(e)
                }
            } catch (e: Exception) {
                failList(e)
            }
        }
    }

    private fun failList(e: Exception) {
        val err = e.toUiError()
        updateState { it.copy(loadingList = false, error = if (it.files.isEmpty()) err else null) }
        if (currentState.files.isNotEmpty()) sendEffect(ShareVisitEffect.Failed(err))
    }

    private fun applyPage(page: ListPage, append: Boolean) {
        val p = page.pagination
        nextToken = p.nextToken?.takeIf { it.isNotEmpty() }
        nextPage = if (nextToken == null && p.hasMore) p.page + 1 else null
        updateState { s ->
            val merged = if (append) {
                val known = s.files.mapTo(HashSet()) { it.path }
                s.files + page.files.filter { it.path !in known }
            } else {
                page.files
            }
            s.copy(
                files = merged,
                loadingList = false,
                loadingMore = false,
                hasMore = p.hasMore,
                error = null,
                contextHint = page.contextHint ?: s.contextHint,
                singleFile = if (page.singleFileView) merged.firstOrNull() else null,
            )
        }
    }

    private fun loadMore() {
        val s = currentState
        val uri = s.currentUri ?: return
        if (!s.hasMore || s.loadingMore || s.loadingList) return
        val token = nextToken
        val page = nextPage
        listJob = scope.launch {
            updateState { it.copy(loadingMore = true) }
            try {
                applyPage(files.listPage(uri, page = page, nextPageToken = token), append = true)
                if (currentState.readme == null) CrUri.parseOrNull(uri)?.let { detectReadme(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingMore = false) }
                sendEffect(ShareVisitEffect.Failed(e.toUiError()))
            }
        }
    }

    /** README：仅目录分享且 `show_readme`；先在已加载列表中按优先级找，列表未取全时再按名称取信息 */
    private suspend fun detectReadme(dir: CrUri) {
        val share = currentState.share ?: return
        if (!share.showReadme || !share.isFolder || currentState.singleFile != null) return
        val loaded = currentState.files
        val found = README_NAMES.firstNotNullOfOrNull { name -> loaded.firstOrNull { !it.isFolder && it.name == name } }
            ?: if (currentState.hasMore) {
                README_NAMES.firstNotNullOfOrNull { name ->
                    try {
                        files.info(dir.join(name).toString()).takeIf { !it.isFolder }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }
        if (found != null && currentState.currentUri == dir.toString()) updateState { it.copy(readme = found) }
    }

    private fun save(dstUri: String) {
        val s = currentState
        if (!s.canSave) return
        val share = s.share ?: return
        val target = s.shortcutTarget ?: return
        val ownerId = share.owner?.id ?: return
        val name = share.name ?: return
        scope.launch {
            updateState { it.copy(saving = true) }
            try {
                val shortcut = files.createShareShortcut(dstUri, name, share.isFolder, target, ownerId)
                updateState { it.copy(saving = false) }
                sendEffect(ShareVisitEffect.Saved(dstUri, shortcut))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(saving = false) }
                sendEffect(ShareVisitEffect.Failed(e.toUiError()))
            }
        }
    }

    companion object {
        /** README 优先级（高 → 低） */
        val README_NAMES: List<String> = listOf("README.md", "README.txt")
    }
}
