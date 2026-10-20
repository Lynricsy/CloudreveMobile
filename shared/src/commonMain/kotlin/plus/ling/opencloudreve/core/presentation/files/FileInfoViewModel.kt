package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CustomPropDefinition
import plus.ling.opencloudreve.core.model.EntityType
import plus.ling.opencloudreve.core.model.FileEntity
import plus.ling.opencloudreve.core.model.FileHashItem
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.MetadataPatch
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class FileInfoState(
    val uri: String,
    val file: FileItem? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    /** 目录统计仍在计算（`folder_summary.completed == false`），每 5 秒刷新 */
    val summaryPending: Boolean = false,
    /** 主版本实体的哈希状态（`POST /file/hash`） */
    val hash: FileHashItem? = null,
    /** 站点自定义属性定义（值在元数据 `props:<id>`） */
    val customProps: List<CustomPropDefinition> = emptyList(),
    /** 正在执行的修改 */
    val mutating: Boolean = false,
) {
    /** 历史版本实体（`type == 0`），新到旧 */
    val versions: List<FileEntity>
        get() = file?.extendedInfo?.entities.orEmpty()
            .filter { it.type == EntityType.VERSION }
            .sortedByDescending { it.createdAt }

    /** 标签：名称 → 颜色（`tag:<名>` 元数据） */
    val tags: Map<String, String>
        get() = file?.metadata.orEmpty()
            .filterKeys { it.startsWith(MetadataKeys.TAG_PREFIX) }
            .mapKeys { it.key.removePrefix(MetadataKeys.TAG_PREFIX) }
}

sealed interface FileInfoIntent {
    data object Refresh : FileInfoIntent

    /** 查询哈希；[compute] 为 true 时为缺失的哈希创建计算任务 */
    data class LoadHash(val compute: Boolean = false) : FileInfoIntent

    /** 修改元数据（标签、自定义属性、图标/emoji 等） */
    data class PatchMetadata(val patches: List<MetadataPatch>) : FileInfoIntent
    data class SetCurrentVersion(val entityId: String) : FileInfoIntent
    data class DeleteVersion(val entityId: String) : FileInfoIntent
    data class DeleteSourceLink(val id: String) : FileInfoIntent
}

sealed interface FileInfoEffect {
    /** 修改失败（锁冲突时 kind 为 LockConflict，平台可引导到 FileActionViewModel 处理） */
    data class Failed(val error: UiError) : FileInfoEffect

    /** 修改成功（文件列表可刷新） */
    data object Updated : FileInfoEffect
}

/**
 * 文件信息页（`GET /file/info?extended=true&folder_summary=true`）。
 * [uri] 应为链接目标（软链接文件传 `linkedUri`）。
 */
class FileInfoViewModel(
    val accountId: String,
    uri: String,
    private val files: FileRepository,
    private val site: SiteConfigRepository,
    private val summaryPollInterval: Duration = 5.seconds,
) : CoreViewModel<FileInfoState, FileInfoIntent, FileInfoEffect>(FileInfoState(uri = uri)) {

    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        scope.launch {
            val defs = try {
                site.explorer(accountId).customProps
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                site.cachedExplorer(accountId)?.customProps.orEmpty()
            }
            updateState { it.copy(customProps = defs) }
        }
        load(showLoading = true)
    }

    override fun on(intent: FileInfoIntent) {
        when (intent) {
            FileInfoIntent.Refresh -> load(showLoading = false)
            is FileInfoIntent.LoadHash -> loadHash(intent.compute)
            is FileInfoIntent.PatchMetadata -> mutate { files.patchMetadata(listOf(currentState.uri), intent.patches) }
            is FileInfoIntent.SetCurrentVersion -> mutate { files.setVersion(currentState.uri, intent.entityId) }
            is FileInfoIntent.DeleteVersion -> mutate { files.deleteVersion(currentState.uri, intent.entityId) }
            is FileInfoIntent.DeleteSourceLink -> mutate { files.deleteSourceLink(intent.id) }
        }
    }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (showLoading) updateState { it.copy(loading = true, error = null) }
            try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loading = false, error = if (it.file == null) e.toUiError() else null) }
                if (currentState.file != null) sendEffect(FileInfoEffect.Failed(e.toUiError()))
            }
        }
    }

    private suspend fun fetch() {
        val uri = currentState.uri
        val first = files.info(uri, extended = true, folderSummary = true)
        apply(first)
        pollJob?.cancel()
        if (first.isFolder && first.folderSummary?.completed == false) {
            pollJob = scope.launch {
                while (true) {
                    delay(summaryPollInterval)
                    val next = try {
                        files.info(uri, extended = true, folderSummary = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        continue
                    }
                    apply(next)
                    if (next.folderSummary?.completed != false) break
                }
            }
        }
    }

    private fun apply(file: FileItem) {
        updateState {
            it.copy(
                file = file,
                loading = false,
                error = null,
                summaryPending = file.isFolder && file.folderSummary?.completed == false,
            )
        }
    }

    private fun loadHash(compute: Boolean) {
        scope.launch {
            try {
                val item = files.hash(listOf(currentState.uri), compute).items.firstOrNull()
                updateState { it.copy(hash = item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(FileInfoEffect.Failed(e.toUiError()))
            }
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (currentState.mutating) return
        scope.launch {
            updateState { it.copy(mutating = true) }
            try {
                block()
                fetch()
                updateState { it.copy(mutating = false) }
                sendEffect(FileInfoEffect.Updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(mutating = false) }
                sendEffect(FileInfoEffect.Failed(e.toUiError()))
            }
        }
    }
}
