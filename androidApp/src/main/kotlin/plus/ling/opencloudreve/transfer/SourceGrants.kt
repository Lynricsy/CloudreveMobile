package plus.ling.opencloudreve.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.transfer.TransferState

/**
 * 上传源 content URI 的持久读授权管理。授权属于“应用 + URI”而不是某个任务：
 * 同一 URI 可被多个任务引用（先暂停 A，再把同一文件传到另一目录 B），只能在最后一个仍需要它的任务结束后释放。
 *
 * - 引用关系以传输任务表为准（[references]）：排队/进行中/暂停/失败的任务仍需要源，已完成/已取消的不需要；
 * - 选择器刚取得授权、尚未建任务的 URI 记为认领（[claim]）：认领之后还没有任何任务入库、且未超过 [claimTtlMs] 时不释放，
 *   避免“检查无引用 → 释放”与“新任务已取得授权尚未入库”交错；
 * - 认领与释放判断在同一把锁内串行。
 */
internal class SourceGrants(
    private val references: suspend (sourceRef: String) -> List<SourceReference>,
    private val releaseGrant: (sourceRef: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val claimTtlMs: Long = CLAIM_TTL_MS,
) {
    /** 引用某个源的传输任务 */
    data class SourceReference(val taskId: String, val state: TransferState, val createdAt: Long)

    private val mutex = Mutex()
    private val claims = HashMap<String, Long>()

    /** 选择器为新上传取得（或再次取得）了 [sourceRef] 的持久授权 */
    suspend fun claim(sourceRef: String) = mutex.withLock { claims[sourceRef] = clock() }

    /**
     * 任务 [taskId]（创建于 [taskCreatedAt]）不再需要 [sourceRef]：没有其他任务仍需要、
     * 也没有晚于全部已知任务的待入库认领时释放授权。返回是否已释放
     */
    suspend fun release(sourceRef: String, taskId: String, taskCreatedAt: Long): Boolean = mutex.withLock {
        val now = clock()
        claims.entries.removeAll { now - it.value > claimTtlMs }
        val others = references(sourceRef).filter { it.taskId != taskId }
        if (others.any { it.state != TransferState.COMPLETED && it.state != TransferState.CANCELED }) return@withLock false
        val latestTask = (others.map { it.createdAt } + taskCreatedAt).max()
        val claimedAt = claims[sourceRef]
        if (claimedAt != null && claimedAt > latestTask) return@withLock false
        claims.remove(sourceRef)
        releaseGrant(sourceRef)
        true
    }

    companion object {
        /** 选择器返回到建任务入库之间的最长间隔 */
        const val CLAIM_TTL_MS = 2 * 60_000L
    }
}
