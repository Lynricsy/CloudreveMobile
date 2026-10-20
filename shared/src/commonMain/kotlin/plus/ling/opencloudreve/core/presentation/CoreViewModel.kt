package plus.ling.opencloudreve.core.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 共享 ViewModel 基类（单向数据流）。
 *
 * - [S] 不可变状态（`data class`），通过 [state] 暴露；
 * - [I] 意图（`sealed interface`，SKIE 转为 Swift enum），平台调用 [on] 投递；
 * - [E] 一次性事件（导航、提示），通过 [effects] 暴露，Channel 缓冲；不需要事件的 VM 用 `Nothing`。
 *
 * Android 由 `ViewModelStore` 清理（`addCloseable` 取消 [scope]）；Swift 包装器在 `deinit` 时调用 [dispose]。
 */
abstract class CoreViewModel<S : Any, I : Any, E : Any>(initial: S) : ViewModel() {
    private val mutableState = MutableStateFlow(initial)
    private val effectChannel = Channel<E>(Channel.BUFFERED)

    /** 当前界面状态 */
    val state: StateFlow<S> = mutableState.asStateFlow()

    /** 一次性事件流（每个事件只投递给一个收集者） */
    val effects: Flow<E> = effectChannel.receiveAsFlow()

    /** VM 生命周期内的协程作用域（主线程，子协程失败互不影响） */
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        addCloseable(object : AutoCloseable {
            override fun close() = scope.cancel()
        })
    }

    /** 处理平台投递的意图 */
    abstract fun on(intent: I)

    /** 给 Swift 包装器在 deinit 时调用：取消所有在途工作。之后 VM 不再可用 */
    fun dispose() {
        scope.cancel()
        effectChannel.close()
    }

    /** 当前状态快照 */
    protected val currentState: S get() = mutableState.value

    /** 原子地更新状态 */
    protected fun updateState(reducer: (S) -> S) {
        mutableState.update(reducer)
    }

    /** 发送一次性事件（缓冲 64 条；VM 已 [dispose] 时忽略） */
    protected fun sendEffect(effect: E) {
        effectChannel.trySend(effect)
    }

    /** 在 [scope] 中启动协程；非取消异常映射为 [UiError] 交给 [onError] */
    protected fun launchCatching(
        onError: (UiError) -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e.toUiError())
        }
    }
}
