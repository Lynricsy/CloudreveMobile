package plus.ling.opencloudreve.core.presentation.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferManager
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadRejection
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 传输列表中的一行：任务快照 + 实时速度 + 可用操作 */
data class TransferRow(
    val item: TransferItem,
    /** 字节/秒（按 bytesDone 在滑动窗口内的差分；非运行中为 0） */
    val speedBytesPerSecond: Long = 0,
    /** 预计剩余秒数；速度未知为 null */
    val etaSeconds: Long? = null,
) {
    val id: String get() = item.id
    val state: TransferState get() = item.state

    /** 预校验失败的原因（`errorCode == VALIDATION` 时） */
    val rejection: UploadRejection? get() = item.rejection()

    val canPause: Boolean get() = item.state.isActive
    val canResume: Boolean get() = item.state == TransferState.PAUSED

    /** 普通重试（失败且不是同名冲突/预校验失败） */
    val canRetry: Boolean get() = item.state == TransferState.FAILED && !item.isConflict && rejection == null
    /** 未结束（含失败/暂停）均可取消：同时清理服务端上传会话 */
    val canCancel: Boolean get() = !item.state.isTerminal

    /** 同名冲突（40004）：提供“重命名/覆盖”重试 */
    val needsConflictResolution: Boolean get() = item.kind == TransferKind.UPLOAD && item.isConflict
}

data class TransferListState(
    val uploads: List<TransferRow> = emptyList(),
    val downloads: List<TransferRow> = emptyList(),
    /** 已收到第一批数据 */
    val loaded: Boolean = false,
    /** 正在执行操作的任务 id */
    val busyIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = uploads.isEmpty() && downloads.isEmpty()

    /** 是否有可“清除已完成”的任务（已完成/已取消） */
    val hasFinished: Boolean get() = (uploads + downloads).any { it.state.isTerminal }

    /** 活动任务数（底栏徽标） */
    val activeCount: Int get() = (uploads + downloads).count { it.state.isActive }

    /** 全部运行中任务的总速度 */
    val totalSpeedBytesPerSecond: Long get() = (uploads + downloads).sumOf { it.speedBytesPerSecond }
}

sealed interface TransferListIntent {
    data class Pause(val id: String) : TransferListIntent
    data class Resume(val id: String) : TransferListIntent
    data class Cancel(val id: String) : TransferListIntent

    /** 失败任务原样重试 */
    data class Retry(val id: String) : TransferListIntent

    /** 同名冲突：改名后重试 */
    data class RetryWithRename(val id: String, val newName: String) : TransferListIntent

    /** 同名冲突：覆盖为新版本后重试 */
    data class RetryWithOverwrite(val id: String) : TransferListIntent

    /** 从列表移除（未结束的先取消） */
    data class Remove(val id: String) : TransferListIntent
    data object ClearFinished : TransferListIntent
}

sealed interface TransferListEffect {
    data class Failed(val error: UiError) : TransferListEffect
}

/**
 * 传输列表（任务 Tab 第一段）。订阅本账户的任务表，按 [sampleInterval] 采样 bytesDone，
 * 在 [speedWindow] 滑动窗口内差分得到速度；没有运行中任务时停止采样。
 */
class TransferListViewModel(
    val accountId: String,
    private val transfers: TransferManager,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sampleInterval: Duration = 1.seconds,
    private val speedWindow: Duration = 5.seconds,
) : CoreViewModel<TransferListState, TransferListIntent, TransferListEffect>(TransferListState()) {

    /** 每个任务的 (时间毫秒, bytesDone) 采样 */
    private val samples = HashMap<String, ArrayDeque<Pair<Long, Long>>>()
    private var items: List<TransferItem> = emptyList()
    private var tickJob: Job? = null

    init {
        scope.launch {
            transfers.observeTasks(accountId).collect { list ->
                items = list
                recompute()
                updateTicker()
            }
        }
    }

    override fun on(intent: TransferListIntent) {
        when (intent) {
            is TransferListIntent.Pause -> act(intent.id) { transfers.pause(it) }
            is TransferListIntent.Resume -> act(intent.id) { transfers.resume(it) }
            is TransferListIntent.Cancel -> act(intent.id) { transfers.cancel(it) }
            is TransferListIntent.Retry -> act(intent.id) { transfers.retry(it) }
            is TransferListIntent.RetryWithRename -> {
                val name = intent.newName.trim()
                if (name.isEmpty()) return
                act(intent.id) { transfers.retry(it, newName = name) }
            }
            is TransferListIntent.RetryWithOverwrite -> act(intent.id) { transfers.retry(it, overwrite = true) }
            is TransferListIntent.Remove -> act(intent.id) { transfers.remove(it) }
            TransferListIntent.ClearFinished -> scope.launch {
                try {
                    transfers.clearFinished(accountId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sendEffect(TransferListEffect.Failed(e.toUiError()))
                }
            }
        }
    }

    private fun act(id: String, block: suspend (String) -> Unit) {
        if (id in currentState.busyIds) return
        updateState { it.copy(busyIds = it.busyIds + id) }
        scope.launch {
            try {
                block(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(TransferListEffect.Failed(e.toUiError()))
            } finally {
                updateState { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private fun updateTicker() {
        val running = items.any { it.state == TransferState.RUNNING }
        if (running && tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(sampleInterval)
                    recompute()
                }
            }
        } else if (!running) {
            tickJob?.cancel()
            tickJob = null
        }
    }

    /** 采样一次并重建行 */
    private fun recompute() {
        val now = clockMillis()
        val windowMs = speedWindow.inWholeMilliseconds
        val liveIds = HashSet<String>()
        val rows = items.map { item ->
            if (item.state != TransferState.RUNNING) return@map TransferRow(item)
            liveIds += item.id
            val deque = samples.getOrPut(item.id) { ArrayDeque() }
            val last = deque.lastOrNull()
            when {
                // 字节数回退（会话重建/重新开始）：重新采样
                last != null && item.bytesDone < last.second -> {
                    deque.clear()
                    deque.addLast(now to item.bytesDone)
                }
                last != null && last.first == now -> deque[deque.lastIndex] = now to item.bytesDone
                else -> deque.addLast(now to item.bytesDone)
            }
            // 淘汰窗口外的样本，但至少保留两个（长时间无变化时以最近两次采样计算）
            while (deque.size > 2 && deque.first().first < now - windowMs) deque.removeFirst()
            val first = deque.first()
            val newest = deque.last()
            val dt = newest.first - first.first
            val speed = if (dt > 0) ((newest.second - first.second) * 1000 / dt).coerceAtLeast(0) else 0L
            val remaining = (item.size - item.bytesDone).coerceAtLeast(0)
            TransferRow(
                item = item,
                speedBytesPerSecond = speed,
                etaSeconds = if (speed > 0 && item.size > 0) (remaining + speed - 1) / speed else null,
            )
        }
        samples.keys.retainAll(liveIds)
        updateState { s ->
            s.copy(
                uploads = rows.filter { it.item.kind == TransferKind.UPLOAD },
                downloads = rows.filter { it.item.kind == TransferKind.DOWNLOAD },
                loaded = true,
            )
        }
    }
}
