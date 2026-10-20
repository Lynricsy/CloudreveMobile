package plus.ling.opencloudreve.core.presentation.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.LockConflict
import plus.ling.opencloudreve.core.api.LockConflictException
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.MetadataPatch
import plus.ling.opencloudreve.core.model.SourceLink
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError

/** 可执行的文件操作（uri 均为 `cloudreve://` 字符串） */
sealed interface FileOperation {
    data class Rename(val uri: String, val newName: String) : FileOperation
    data class Move(val uris: List<String>, val dst: String) : FileOperation

    /** 复制；也用于“保存分享到我的文件” */
    data class Copy(val uris: List<String>, val dst: String) : FileOperation

    /** 删除：默认进回收站；[skipSoftDelete] 彻底删除（回收站内必须为 true）；[unlink] 仅解除链接 */
    data class Delete(val uris: List<String>, val unlink: Boolean = false, val skipSoftDelete: Boolean = false) : FileOperation
    data class Restore(val uris: List<String>) : FileOperation
    data object EmptyTrash : FileOperation
    data class Pin(val uri: String, val name: String? = null) : FileOperation
    data class Unpin(val uri: String) : FileOperation
    data class CreateFolder(val parentUri: String, val name: String) : FileOperation
    data class CreateFile(val parentUri: String, val name: String) : FileOperation
    data class CreateSourceLinks(val uris: List<String>) : FileOperation
    data class DeleteSourceLink(val id: String) : FileOperation

    /** 设为/取消“不生成缩略图”（`thumb:disabled`） */
    data class SetThumbnailDisabled(val uris: List<String>, val disabled: Boolean) : FileOperation
    data class PatchMetadata(val uris: List<String>, val patches: List<MetadataPatch>) : FileOperation

    /** 目录挂载存储策略；空串恢复继承 */
    data class MountPolicy(val uri: String, val policyId: String) : FileOperation
}

/** 操作成功的结果 */
sealed interface FileOperationOutcome {
    data object Done : FileOperationOutcome
    data class Created(val file: FileItem) : FileOperationOutcome
    data class Renamed(val file: FileItem) : FileOperationOutcome

    /** 直链：成功的链接与失败项（uri → 错误） */
    data class SourceLinks(val links: List<SourceLink>, val failures: List<OperationFailure>) : FileOperationOutcome
}

/** 批量操作中单个 uri 的失败 */
data class OperationFailure(val uri: String, val error: UiError)

/** 锁冲突提示：列出冲突项，用户可选择强制解锁并重试 [operation] */
data class LockConflictPrompt(val operation: FileOperation, val conflicts: List<LockConflict>)

data class FileActionState(
    /** 正在执行的操作 */
    val running: FileOperation? = null,
    /** 40073 锁冲突待处理 */
    val lockConflict: LockConflictPrompt? = null,
    /** 最近一次批量操作（40081）的部分失败项 */
    val partialFailures: List<OperationFailure> = emptyList(),
) {
    val busy: Boolean get() = running != null
}

sealed interface FileActionIntent {
    data class Run(val operation: FileOperation) : FileActionIntent

    /** 对当前锁冲突的全部 token 强制解锁（`DELETE /file/lock`）后重放原操作 */
    data object ForceUnlockAndRetry : FileActionIntent
    data object DismissConflict : FileActionIntent
    data object DismissFailures : FileActionIntent
}

sealed interface FileActionEffect {
    /** 操作成功：平台刷新列表并提示 */
    data class Succeeded(val operation: FileOperation, val outcome: FileOperationOutcome) : FileActionEffect

    /** 操作失败（含部分失败 [ErrorKind.PartialFailure]，失败项见状态 `partialFailures`）；锁冲突不发此事件而是进入状态 */
    data class Failed(val operation: FileOperation, val error: UiError) : FileActionEffect
}

/**
 * 文件操作执行器：串行执行一个操作；40073 进入 [FileActionState.lockConflict] 等待“强制解锁并重试”；
 * 40081 把 `aggregated_error` 汇总到 [FileActionState.partialFailures]。
 */
class FileActionViewModel(
    val accountId: String,
    private val files: FileRepository,
) : CoreViewModel<FileActionState, FileActionIntent, FileActionEffect>(FileActionState()) {

    override fun on(intent: FileActionIntent) {
        when (intent) {
            is FileActionIntent.Run -> execute(intent.operation)
            FileActionIntent.ForceUnlockAndRetry -> forceUnlockAndRetry()
            FileActionIntent.DismissConflict -> updateState { it.copy(lockConflict = null) }
            FileActionIntent.DismissFailures -> updateState { it.copy(partialFailures = emptyList()) }
        }
    }

    private fun execute(operation: FileOperation) {
        if (currentState.busy) return
        updateState { it.copy(running = operation, lockConflict = null, partialFailures = emptyList()) }
        scope.launch { runGuarded(operation) }
    }

    private fun forceUnlockAndRetry() {
        val prompt = currentState.lockConflict ?: return
        if (currentState.busy) return
        updateState { it.copy(running = prompt.operation, lockConflict = null) }
        scope.launch {
            val tokens = prompt.conflicts.mapNotNull { it.token?.takeIf(String::isNotEmpty) }
            try {
                if (tokens.isNotEmpty()) files.unlock(tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(running = null) }
                sendEffect(FileActionEffect.Failed(prompt.operation, e.toUiError()))
                return@launch
            }
            runGuarded(prompt.operation)
        }
    }

    private suspend fun runGuarded(operation: FileOperation) {
        try {
            val outcome = perform(operation)
            updateState { it.copy(running = null) }
            sendEffect(FileActionEffect.Succeeded(operation, outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LockConflictException) {
            updateState { it.copy(running = null, lockConflict = LockConflictPrompt(operation, e.conflicts)) }
        } catch (e: ApiException) {
            val failures = if (e.code == ErrorCode.BATCH_PARTIAL) failuresOf(e) else emptyList()
            updateState { it.copy(running = null, partialFailures = failures) }
            sendEffect(FileActionEffect.Failed(operation, e.toUiError()))
        } catch (e: Exception) {
            updateState { it.copy(running = null) }
            sendEffect(FileActionEffect.Failed(operation, e.toUiError()))
        }
    }

    private suspend fun perform(op: FileOperation): FileOperationOutcome = when (op) {
        is FileOperation.Rename -> FileOperationOutcome.Renamed(files.rename(op.uri, op.newName))
        is FileOperation.Move -> files.move(op.uris, op.dst).let { FileOperationOutcome.Done }
        is FileOperation.Copy -> files.copy(op.uris, op.dst).let { FileOperationOutcome.Done }
        is FileOperation.Delete -> files.delete(op.uris, op.unlink, op.skipSoftDelete).let { FileOperationOutcome.Done }
        is FileOperation.Restore -> files.restore(op.uris).let { FileOperationOutcome.Done }
        FileOperation.EmptyTrash -> files.emptyTrash().let { FileOperationOutcome.Done }
        is FileOperation.Pin -> files.pin(op.uri, op.name).let { FileOperationOutcome.Done }
        is FileOperation.Unpin -> files.unpin(op.uri).let { FileOperationOutcome.Done }
        is FileOperation.CreateFolder -> FileOperationOutcome.Created(files.createFolder(op.parentUri, op.name))
        is FileOperation.CreateFile -> FileOperationOutcome.Created(files.createFile(op.parentUri, op.name))
        is FileOperation.CreateSourceLinks -> {
            val result = files.createSourceLinks(op.uris)
            FileOperationOutcome.SourceLinks(
                links = result.links,
                failures = result.failures.map { (uri, env) -> OperationFailure(uri, ApiException(env.code, env.msg).toUiError()) },
            )
        }
        is FileOperation.DeleteSourceLink -> files.deleteSourceLink(op.id).let { FileOperationOutcome.Done }
        is FileOperation.SetThumbnailDisabled -> {
            if (op.disabled) {
                files.patchMetadata(op.uris, listOf(MetadataPatch(MetadataKeys.THUMB_DISABLED, "")))
            } else {
                files.resetThumbnails(op.uris)
            }
            FileOperationOutcome.Done
        }
        is FileOperation.PatchMetadata -> files.patchMetadata(op.uris, op.patches).let { FileOperationOutcome.Done }
        is FileOperation.MountPolicy -> files.mountPolicy(op.uri, op.policyId).let { FileOperationOutcome.Done }
    }

    private fun failuresOf(e: ApiException): List<OperationFailure> =
        e.aggregated.orEmpty().map { (uri, env) ->
            OperationFailure(uri, ApiException(env.code, env.msg).toUiError())
        }
}
