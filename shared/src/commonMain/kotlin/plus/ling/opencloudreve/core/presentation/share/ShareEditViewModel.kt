package plus.ling.opencloudreve.core.presentation.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.model.ShareCreateRequest
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.share.ShareRepository
import kotlin.time.Clock
import kotlin.time.Instant

/** 保存成功后的链接 */
data class ShareEditResult(
    val shareId: String,
    /** 完整链接（含密码时为 `<site>/s/<id>/<pw>`） */
    val link: String,
    /** 去掉密码段的链接 */
    val linkWithoutPassword: String,
    val password: String?,
)

data class ShareEditState(
    /** 源文件 URI（编辑时从 `source_uri` 取得） */
    val fileUri: String?,
    /** 编辑的分享 id；新建为 null */
    val shareId: String?,
    val name: String = "",
    val isFolder: Boolean = false,
    val loading: Boolean = false,
    /** 加载源文件/分享失败（整页错误） */
    val loadError: UiError? = null,
    /** 私密分享（需要密码） */
    val isPrivate: Boolean = false,
    /** 自定义密码；私密且留空时服务端生成 8 位小写字母密码 */
    val password: String = "",
    /** 剩余下载次数上限；null 不限（仅文件） */
    val downloadLimit: Int? = null,
    /** 有效期（秒，相对现在）；null 永久 */
    val expireSeconds: Long? = null,
    /** 编辑时原有的到期时间（用户未改有效期时展示） */
    val originalExpires: Instant? = null,
    /** 允许访客浏览目录（仅目录） */
    val shareView: Boolean = false,
    /** 自动展示 README（仅目录） */
    val showReadme: Boolean = false,
    val submitting: Boolean = false,
    val result: ShareEditResult? = null,
) {
    val isEditing: Boolean get() = shareId != null

    /** 密码：留空或 1–32 位字母数字 */
    val passwordValid: Boolean get() = !isPrivate || password.isEmpty() || PASSWORD_PATTERN.matches(password)
    val downloadLimitValid: Boolean get() = downloadLimit == null || downloadLimit > 0
    val expireValid: Boolean get() = expireSeconds == null || expireSeconds > 0

    /** 下载次数仅对文件有效；浏览/README 选项仅对目录有效 */
    val showDownloadLimit: Boolean get() = !isFolder
    val showFolderOptions: Boolean get() = isFolder

    val canSubmit: Boolean
        get() = fileUri != null && !loading && loadError == null && !submitting && passwordValid && downloadLimitValid && expireValid

    companion object {
        private val PASSWORD_PATTERN = Regex("^[A-Za-z0-9]{1,32}$")

        /** 有效期预设（秒）：5 分钟、1 小时、1 天、7 天、30 天 */
        val EXPIRE_PRESETS: List<Long> = listOf(300, 3600, 86_400, 604_800, 2_592_000)

        /** 下载次数预设 */
        val DOWNLOAD_PRESETS: List<Int> = listOf(1, 2, 3, 4, 5, 20, 50, 100)
    }
}

sealed interface ShareEditIntent {
    data class SetPrivate(val enabled: Boolean) : ShareEditIntent
    data class SetPassword(val password: String) : ShareEditIntent

    /** null 不限 */
    data class SetDownloadLimit(val limit: Int?) : ShareEditIntent

    /** null 永久 */
    data class SetExpire(val seconds: Long?) : ShareEditIntent
    data class SetShareView(val enabled: Boolean) : ShareEditIntent
    data class SetShowReadme(val enabled: Boolean) : ShareEditIntent
    data object Submit : ShareEditIntent
    data object Reload : ShareEditIntent
}

sealed interface ShareEditEffect {
    /** 保存成功（平台展示链接/二维码/系统分享） */
    data class Saved(val result: ShareEditResult) : ShareEditEffect
    data class Failed(val error: UiError) : ShareEditEffect
}

/**
 * 新建（[fileUri]）或编辑（[shareId]，源文件由 `owner_extended` 的 `source_uri` 取得）分享。
 * 编辑时未修改有效期则按剩余秒数提交，避免清掉原有到期时间。
 */
class ShareEditViewModel(
    val accountId: String,
    fileUri: String?,
    shareId: String?,
    private val shares: ShareRepository,
    private val files: FileRepository,
    private val clock: () -> Instant = { Clock.System.now() },
) : CoreViewModel<ShareEditState, ShareEditIntent, ShareEditEffect>(
    ShareEditState(
        fileUri = fileUri,
        shareId = shareId,
        name = fileUri?.let { CrUri.parseOrNull(it)?.name() }.orEmpty(),
    ),
) {
    /** 用户是否改过有效期（编辑时决定是否保留原到期时间） */
    private var expireTouched = false

    init {
        require(fileUri != null || shareId != null) { "fileUri or shareId required" }
        load()
    }

    override fun on(intent: ShareEditIntent) {
        when (intent) {
            is ShareEditIntent.SetPrivate -> updateState { it.copy(isPrivate = intent.enabled) }
            is ShareEditIntent.SetPassword -> updateState { it.copy(password = intent.password.trim()) }
            is ShareEditIntent.SetDownloadLimit -> updateState { it.copy(downloadLimit = intent.limit) }
            is ShareEditIntent.SetExpire -> {
                expireTouched = true
                updateState { it.copy(expireSeconds = intent.seconds, originalExpires = null) }
            }
            is ShareEditIntent.SetShareView -> updateState { it.copy(shareView = intent.enabled) }
            is ShareEditIntent.SetShowReadme -> updateState { it.copy(showReadme = intent.enabled) }
            ShareEditIntent.Submit -> submit()
            ShareEditIntent.Reload -> load()
        }
    }

    private fun load() {
        scope.launch {
            updateState { it.copy(loading = true, loadError = null) }
            try {
                val id = currentState.shareId
                if (id != null) {
                    // 带密码的分享：服务端解析 owner_extended 的 source_uri 时要校验密码（网页端同样传入密码），
                    // 所以先取基本信息（所有者可见密码），再带密码取扩展信息
                    val basic = shares.info(id)
                    applyShare(shares.info(id, password = basic.password?.ifEmpty { null }, ownerExtended = true))
                } else {
                    val file = files.info(currentState.fileUri!!)
                    updateState { it.copy(name = file.name, isFolder = file.isFolder, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, loadError = e.toUiError()) }
            }
        }
    }

    private fun applyShare(share: Share) {
        val now = clock()
        val expires = share.expires
        val remaining = expires?.let { (it - now).inWholeSeconds }
        updateState {
            it.copy(
                fileUri = share.sourceUri ?: it.fileUri,
                name = share.name ?: it.name,
                isFolder = share.isFolder,
                loading = false,
                isPrivate = share.isPrivate || share.passwordProtected,
                password = share.password.orEmpty(),
                downloadLimit = share.remainDownloads?.takeIf { d -> d > 0 },
                expireSeconds = remaining?.coerceAtLeast(1),
                originalExpires = expires,
                shareView = share.shareView,
                showReadme = share.showReadme,
                loadError = if (share.sourceUri == null && it.fileUri == null) UiError(ErrorKind.NotFound) else null,
            )
        }
    }

    private fun submit() {
        val s = currentState
        if (!s.canSubmit) return
        val uri = s.fileUri ?: return
        // 编辑且未改有效期：按当前剩余秒数重新提交
        val expire = if (s.isEditing && !expireTouched && s.originalExpires != null) {
            (s.originalExpires - clock()).inWholeSeconds.coerceAtLeast(1)
        } else {
            s.expireSeconds ?: 0
        }
        val request = ShareCreateRequest(
            uri = uri,
            isPrivate = s.isPrivate,
            password = if (s.isPrivate) s.password.ifEmpty { null } else null,
            downloads = if (s.isFolder) 0 else s.downloadLimit ?: 0,
            expire = expire.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            shareView = s.isFolder && s.shareView,
            showReadme = s.isFolder && s.showReadme,
        )
        scope.launch {
            updateState { it.copy(submitting = true) }
            try {
                val link = if (s.shareId != null) shares.update(s.shareId, request) else shares.create(request)
                val parsed = ShareLinkParser.splitCreated(link)
                val id = parsed?.id ?: s.shareId.orEmpty()
                val pw = parsed?.password
                val result = ShareEditResult(
                    shareId = id,
                    link = link,
                    linkWithoutPassword = if (pw != null) link.substringBeforeLast('/') else link,
                    password = pw,
                )
                updateState { it.copy(submitting = false, result = result, shareId = id, password = pw ?: it.password) }
                sendEffect(ShareEditEffect.Saved(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(submitting = false) }
                sendEffect(ShareEditEffect.Failed(e.toUiError()))
            }
        }
    }
}
