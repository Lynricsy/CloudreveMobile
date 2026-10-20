package plus.ling.opencloudreve.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.transfer.TransferExecutor
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.TransferState
import java.util.concurrent.TimeUnit

/**
 * WorkManager 实现的传输执行器：每个任务一个唯一 Work（名称 = taskId，`ExistingWorkPolicy.KEEP`，需要联网），
 * 上传由 [TransferWorker]、下载由 [DownloadWorker] 驱动规划器。
 *
 * 暂停 = 取消 Work（DB 进度/会话保留，状态由 TransferManager 写为 paused）；继续 = 重新入队。
 * 进程被杀后 WorkManager 会自行重跑未完成的 Work；[reattach] 再为 DB 中未完成的任务补一次入队（KEEP 不会重复）。
 */
internal class AndroidTransferExecutor(
    context: Context,
    private val repository: TransferRepository,
) : TransferExecutor {
    private val appContext = context.applicationContext
    private val workManager get() = WorkManager.getInstance(appContext)

    override fun enqueue(taskId: String) {
        TransferRuntime.scope.launch { repository.task(taskId)?.let(::schedule) }
    }

    override fun pause(taskId: String) {
        workManager.cancelUniqueWork(taskId)
    }

    override fun resume(taskId: String) = enqueue(taskId)

    override fun cancel(taskId: String) {
        val operation = workManager.cancelUniqueWork(taskId)
        TransferRuntime.scope.launch {
            runCatching { operation.await() }
            val item = repository.task(taskId) ?: return@launch
            cleanup(appContext, item)
        }
    }

    override fun reattach() {
        TransferRuntime.scope.launch {
            runCatching { repository.unfinishedTasks() }.getOrDefault(emptyList()).forEach(::schedule)
        }
    }

    private fun schedule(item: TransferItem) {
        val worker = if (item.kind == TransferKind.UPLOAD) TransferWorker::class.java else DownloadWorker::class.java
        val request = OneTimeWorkRequest.Builder(worker)
            .setInputData(workDataOf(KEY_TASK_ID to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(item.id, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val TAG = "transfer"

        /**
         * 任务不再需要本地临时数据时清理：下载删除未完成的目标文件；上传释放源（收件箱副本/持久授权）。
         * 已完成的下载保留文件。
         */
        fun cleanup(context: Context, item: TransferItem) {
            when (item.kind) {
                TransferKind.DOWNLOAD -> if (item.state != TransferState.COMPLETED) {
                    item.destPath?.let { DownloadTargets.delete(context, it) }
                }
                TransferKind.UPLOAD -> item.sourceRef?.let { UploadSources.discard(context, it) }
            }
        }
    }
}
