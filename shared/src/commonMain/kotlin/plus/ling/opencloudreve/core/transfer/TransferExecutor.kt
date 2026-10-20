package plus.ling.opencloudreve.core.transfer

/**
 * 平台传输执行器（Android WorkManager `TransferWorker`/`DownloadWorker`；iOS 后台 `URLSession`）。
 *
 * 只负责调度平台任务与搬运字节，**不直接修改任务状态**：状态变化由 [TransferManager] 与规划器写入 DB。
 * 所有方法应立即返回（异步调度），可从任意线程调用。
 */
interface TransferExecutor {
    /** 开始（或在已排队时忽略）执行任务：上传走 [UploadPlanner]，下载走 [DownloadPlanner] */
    fun enqueue(taskId: String)

    /** 停止任务的在途请求（iOS 下载应保存 resumeData 到 `TransferRepository.setResumeBlob`） */
    fun pause(taskId: String)

    /** 等价于重新 [enqueue]；规划器会复用仍有效的会话 */
    fun resume(taskId: String)

    /** 中止在途请求并清理该任务的临时文件 */
    fun cancel(taskId: String)

    /** 进程启动时接管 `TransferRepository.unfinishedTasks()`：对齐平台在途任务后继续执行 */
    fun reattach()
}

/** 执行器在运行时由平台安装（`CoreGraph.installExecutor`）；未安装时只记录状态不执行 */
class TransferExecutorHolder {
    var executor: TransferExecutor? = null
        private set

    fun install(executor: TransferExecutor) {
        this.executor = executor
    }
}
