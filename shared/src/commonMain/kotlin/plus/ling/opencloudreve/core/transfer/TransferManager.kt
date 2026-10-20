package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.flow.Flow
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.StoragePolicy

/**
 * 传输入口（UI/ViewModel、系统分享菜单、File Provider 使用）：建任务、预校验、暂停/继续/取消/重试，
 * 并把调度交给已安装的 [TransferExecutor]。
 */
class TransferManager(
    private val repository: TransferRepository,
    private val uploadPlanner: UploadPlanner,
    private val downloadPlanner: DownloadPlanner,
    private val executors: TransferExecutorHolder,
) {
    private val executor: TransferExecutor? get() = executors.executor

    fun observeTasks(accountId: String): Flow<List<TransferItem>> = repository.observeTasks(accountId)

    fun observeSummary(accountId: String? = null): Flow<TransferSummary> = repository.observeSummary(accountId)

    /**
     * 为每个源建上传任务。[policy] 为当前目录列表的 `storage_policy`，用于 [UploadValidator] 预校验：
     * 不通过的任务直接以 failed（`TransferErrorCode.VALIDATION`，`errorMessage` 为 [UploadRejection] 名称）入库，不调度。
     */
    @Throws(Exception::class)
    suspend fun enqueueUploads(
        accountId: String,
        sources: List<UploadSource>,
        targetDirUri: String,
        policy: StoragePolicy?,
        overwrite: Boolean = false,
    ): List<TransferItem> = sources.map { source ->
        val rejection = UploadValidator.validate(source.name, source.size, policy)
        if (rejection != null) {
            repository.createUpload(
                accountId, source, targetDirUri, overwrite,
                initialState = TransferState.FAILED,
                errorCode = TransferErrorCode.VALIDATION,
                errorMessage = rejection.name,
            )
        } else {
            repository.createUpload(accountId, source, targetDirUri, overwrite).also { executor?.enqueue(it.id) }
        }
    }

    /** 建下载任务并调度；[fileUri] 应先用软链接目标（`sys:shared_redirect`）解析 */
    @Throws(Exception::class)
    suspend fun enqueueDownload(
        accountId: String,
        fileUri: String,
        displayName: String,
        size: Long,
        destPath: String,
    ): TransferItem = repository.createDownload(accountId, fileUri, displayName, size, destPath)
        .also { executor?.enqueue(it.id) }

    @Throws(Exception::class)
    suspend fun pause(taskId: String) {
        val task = repository.task(taskId) ?: return
        if (!task.state.isActive) return
        executor?.pause(taskId)
        when (task.kind) {
            TransferKind.UPLOAD -> uploadPlanner.pause(taskId)
            TransferKind.DOWNLOAD -> downloadPlanner.pause(taskId)
        }
    }

    /** 继续已暂停或失败的任务（复用仍有效的会话） */
    @Throws(Exception::class)
    suspend fun resume(taskId: String) {
        val task = repository.task(taskId) ?: return
        if (task.state != TransferState.PAUSED && task.state != TransferState.FAILED) return
        repository.resetAttempts(taskId)
        repository.setState(taskId, TransferState.QUEUED)
        executor?.resume(taskId)
    }

    /**
     * 重试失败的任务。冲突（40004）时传 [newName] 重命名目标，或 [overwrite] = true 覆盖为新版本。
     * 改名/覆盖会丢弃旧会话，重新创建。
     */
    @Throws(Exception::class)
    suspend fun retry(taskId: String, newName: String? = null, overwrite: Boolean? = null) {
        val task = repository.task(taskId) ?: return
        if (task.state != TransferState.FAILED && task.state != TransferState.PAUSED) return
        if (task.kind == TransferKind.UPLOAD && (newName != null || overwrite != null)) {
            val name = newName ?: task.displayName
            val dir = CrUri.parse(task.targetUri).parent() ?: CrUri.parse(task.targetUri)
            uploadPlanner.discardSession(taskId)
            repository.updateTarget(taskId, name, dir.join(name).toString(), overwrite ?: task.overwrite)
        }
        repository.resetAttempts(taskId)
        repository.setState(taskId, TransferState.QUEUED)
        executor?.enqueue(taskId)
    }

    /** 取消：先停止平台任务，再清理服务端上传会话 */
    @Throws(Exception::class)
    suspend fun cancel(taskId: String) {
        val task = repository.task(taskId) ?: return
        if (task.state.isTerminal) return
        executor?.cancel(taskId)
        when (task.kind) {
            TransferKind.UPLOAD -> uploadPlanner.cancel(taskId)
            TransferKind.DOWNLOAD -> downloadPlanner.cancel(taskId)
        }
    }

    /** 从列表移除（未结束的先取消） */
    @Throws(Exception::class)
    suspend fun remove(taskId: String) {
        cancel(taskId)
        repository.delete(taskId)
    }

    /** 清除账户下已完成/已取消的任务 */
    @Throws(Exception::class)
    suspend fun clearFinished(accountId: String) = repository.clearFinished(accountId)

    /** 进程启动时让执行器接管未完成任务 */
    fun reattach() {
        executor?.reattach()
    }
}
