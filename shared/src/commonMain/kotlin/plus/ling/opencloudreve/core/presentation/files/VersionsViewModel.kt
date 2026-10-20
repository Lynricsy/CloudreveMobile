package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.EntityType
import plus.ling.opencloudreve.core.model.FileEntity
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository

/** 版本列表一行 */
data class VersionRow(
    val entity: FileEntity,
    /** 是否为当前版本（`primary_entity`） */
    val isCurrent: Boolean,
    /** 可“设为当前”/“删除”（可管理且非当前版本） */
    val canModify: Boolean,
) {
    val id: String get() = entity.id
}

data class VersionsState(
    val uri: String,
    val file: FileItem? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    /** 新到旧 */
    val versions: List<VersionRow> = emptyList(),
    /** 可管理版本：自己的文件，或管理员在“我的文件”文件系统下 */
    val canManage: Boolean = false,
    /** 正在修改的版本 id */
    val mutatingId: String? = null,
) {
    val name: String get() = file?.name ?: CrUri.parseOrNull(uri)?.name().orEmpty()
}

sealed interface VersionsIntent {
    data object Refresh : VersionsIntent
    data class SetCurrent(val entityId: String) : VersionsIntent
    data class Delete(val entityId: String) : VersionsIntent
}

sealed interface VersionsEffect {
    /** 当前版本已切换/版本已删除（列表与预览可刷新） */
    data object Updated : VersionsEffect
    data class Failed(val error: UiError) : VersionsEffect
}

/**
 * 文件版本（`extended_info.entities` 中 `type == 0`）：设为当前（`POST /file/version/current`）、删除（`DELETE /file/version`）、
 * 预览/下载指定版本（[versionUrl]）。[uri] 应为链接目标。
 */
class VersionsViewModel(
    val accountId: String,
    uri: String,
    private val files: FileRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<VersionsState, VersionsIntent, VersionsEffect>(VersionsState(uri = uri)) {

    private var loadJob: Job? = null

    init {
        load(showLoading = true)
    }

    override fun on(intent: VersionsIntent) {
        when (intent) {
            VersionsIntent.Refresh -> load(showLoading = false)
            is VersionsIntent.SetCurrent -> mutate(intent.entityId) { files.setVersion(currentState.uri, it) }
            is VersionsIntent.Delete -> mutate(intent.entityId) { files.deleteVersion(currentState.uri, it) }
        }
    }

    /** 某个版本的地址（预览 `download=false` / 下载 `download=true`） */
    @Throws(Exception::class)
    suspend fun versionUrl(entityId: String, download: Boolean): String? =
        files.fileUrls(listOf(currentState.uri), download = download, entity = entityId).urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (showLoading) updateState { it.copy(loading = true, error = null) }
            try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e.toUiError()
                updateState { it.copy(loading = false, error = if (it.file == null) err else null) }
                if (currentState.file != null) sendEffect(VersionsEffect.Failed(err))
            }
        }
    }

    private suspend fun fetch() {
        val file = files.info(currentState.uri, extended = true)
        val user = userSettings.currentUserOrNull()
        val canManage = canManage(file, user)
        val rows = file.extendedInfo?.entities.orEmpty()
            .filter { it.type == EntityType.VERSION }
            .sortedByDescending { it.createdAt }
            .map { e ->
                val current = e.id == file.primaryEntity
                VersionRow(e, isCurrent = current, canModify = canManage && !current)
            }
        updateState { it.copy(file = file, versions = rows, canManage = canManage, loading = false, error = null) }
    }

    private fun canManage(file: FileItem, user: User?): Boolean =
        file.owned || (user?.isAdmin == true && CrUri.parseOrNull(file.path)?.fs == CrUri.FS_MY)

    private fun mutate(entityId: String, block: suspend (String) -> Unit) {
        val row = currentState.versions.firstOrNull { it.id == entityId } ?: return
        if (!row.canModify || currentState.mutatingId != null) return
        scope.launch {
            updateState { it.copy(mutatingId = entityId) }
            try {
                block(entityId)
                fetch()
                updateState { it.copy(mutatingId = null) }
                sendEffect(VersionsEffect.Updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(mutatingId = null) }
                sendEffect(VersionsEffect.Failed(e.toUiError()))
            }
        }
    }
}
