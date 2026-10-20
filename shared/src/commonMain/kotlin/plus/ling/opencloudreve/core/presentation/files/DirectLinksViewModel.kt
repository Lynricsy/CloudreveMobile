package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.DirectLink
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository

/** 生成的直链（`PUT /file/source` 结果） */
data class GeneratedDirectLink(
    /** 文件名（取自 `file_url` 的最后一段） */
    val name: String,
    /** 文件 URI */
    val fileUri: String,
    /** 原始直链 */
    val link: String,
    /** 按“强制下载”开关处理后的直链（`/f/` → `/f/d/`） */
    val displayLink: String,
)

/** 已有直链（`extended_info.direct_links`） */
data class ManagedDirectLink(val link: DirectLink, val displayLink: String) {
    val id: String get() = link.id
}

data class DirectLinksState(
    val uris: List<String>,
    /** 单个文件时的文件信息（含已有直链） */
    val file: FileItem? = null,
    val user: User? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    val generating: Boolean = false,
    val generated: List<GeneratedDirectLink> = emptyList(),
    /** 生成失败项（uri → 原因） */
    val failures: List<OperationFailure> = emptyList(),
    /** 单个文件的已有直链 */
    val existing: List<ManagedDirectLink> = emptyList(),
    val deletingId: String? = null,
    /** 链接改为强制下载形式 */
    val forceDownload: Boolean = false,
    /** 复制文本里带 `[文件名]` 前缀 */
    val showFileName: Boolean = false,
) {
    /** 用户组单次直链上限（0 不允许） */
    val batchLimit: Int get() = user?.group?.directLinkBatchSize ?: 0

    /** 超过批量上限（用户未知时不限制，交给服务端） */
    val exceedsLimit: Boolean get() = user != null && uris.size > batchLimit

    /** 目录直链需要 `folder_direct_link` 权限 */
    val folderNotAllowed: Boolean
        get() = file?.isFolder == true && user != null && !user.hasPermission(GroupPermission.FOLDER_DIRECT_LINK)

    val canGenerate: Boolean get() = uris.isNotEmpty() && !generating && !exceedsLimit && !folderNotAllowed

    /** 单个文件可管理已有直链 */
    val canManage: Boolean get() = uris.size == 1

    /** 复制到剪贴板的文本（每行一个） */
    val copyText: String
        get() = generated.joinToString("\n") { if (showFileName) "[${it.name}] ${it.displayLink}" else it.displayLink }
}

sealed interface DirectLinksIntent {
    /** 生成（或取回已有的）永久直链 */
    data object Generate : DirectLinksIntent
    data class SetForceDownload(val enabled: Boolean) : DirectLinksIntent
    data class SetShowFileName(val enabled: Boolean) : DirectLinksIntent

    /** 删除单个文件的某条已有直链 */
    data class Delete(val id: String) : DirectLinksIntent
    data object Refresh : DirectLinksIntent
}

sealed interface DirectLinksEffect {
    data object Generated : DirectLinksEffect
    data object Deleted : DirectLinksEffect
    data class Failed(val error: UiError) : DirectLinksEffect
}

/**
 * 永久直链：对选中文件生成直链（受 `direct_link_batch_size` 与 `folder_direct_link` 限制，40081 部分失败列出失败项），
 * 单个文件时列出并可删除已有直链（`DELETE /file/source/:id`）。
 */
class DirectLinksViewModel(
    val accountId: String,
    uris: List<String>,
    private val files: FileRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<DirectLinksState, DirectLinksIntent, DirectLinksEffect>(DirectLinksState(uris = uris)) {

    init {
        scope.launch {
            val me = userSettings.currentUserOrNull()
            updateState { it.copy(user = me) }
        }
        if (uris.size == 1) loadExisting(showLoading = true)
    }

    override fun on(intent: DirectLinksIntent) {
        when (intent) {
            DirectLinksIntent.Generate -> generate()
            is DirectLinksIntent.SetForceDownload -> updateState { s ->
                s.copy(
                    forceDownload = intent.enabled,
                    generated = s.generated.map { it.copy(displayLink = display(it.link, intent.enabled)) },
                    existing = s.existing.map { it.copy(displayLink = display(it.link.url, intent.enabled)) },
                )
            }
            is DirectLinksIntent.SetShowFileName -> updateState { it.copy(showFileName = intent.enabled) }
            is DirectLinksIntent.Delete -> delete(intent.id)
            DirectLinksIntent.Refresh -> if (currentState.canManage) loadExisting(showLoading = false)
        }
    }

    private fun display(link: String, force: Boolean): String = if (force) link.replace("/f/", "/f/d/") else link

    private fun loadExisting(showLoading: Boolean) {
        scope.launch {
            if (showLoading) updateState { it.copy(loading = true, error = null) }
            try {
                applyFile(files.info(currentState.uris.single(), extended = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    private fun applyFile(file: FileItem) {
        updateState { s ->
            s.copy(
                file = file,
                loading = false,
                error = null,
                existing = file.extendedInfo?.directLinks.orEmpty().map { ManagedDirectLink(it, display(it.url, s.forceDownload)) },
            )
        }
    }

    private fun generate() {
        if (!currentState.canGenerate) return
        scope.launch {
            updateState { it.copy(generating = true, failures = emptyList()) }
            try {
                val result = files.createSourceLinks(currentState.uris)
                val force = currentState.forceDownload
                val links = result.links.map {
                    GeneratedDirectLink(
                        name = CrUri.parseOrNull(it.fileUrl)?.name().orEmpty(),
                        fileUri = it.fileUrl,
                        link = it.link,
                        displayLink = display(it.link, force),
                    )
                }
                val failures = result.failures.map { (uri, env) -> OperationFailure(uri, ApiException(env.code, env.msg).toUiError()) }
                updateState { it.copy(generating = false, generated = links, failures = failures) }
                sendEffect(DirectLinksEffect.Generated)
                if (currentState.canManage) applyFile(files.info(currentState.uris.single(), extended = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(generating = false) }
                sendEffect(DirectLinksEffect.Failed(e.toUiError()))
            }
        }
    }

    private fun delete(id: String) {
        if (currentState.deletingId != null) return
        scope.launch {
            updateState { it.copy(deletingId = id) }
            try {
                files.deleteSourceLink(id)
                updateState { s -> s.copy(deletingId = null, existing = s.existing.filterNot { it.id == id }) }
                sendEffect(DirectLinksEffect.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(deletingId = null) }
                sendEffect(DirectLinksEffect.Failed(e.toUiError()))
            }
        }
    }
}
