import Observation
import OpenCloudreveCore

/// 把共享层 `CoreViewModel` 桥接成 SwiftUI 可观察对象。
///
/// - `state`：镜像 Kotlin `StateFlow`（SKIE 转成 `AsyncSequence`），视图读取即建立观察；
/// - `send(_:)`：转发意图后立即同步拉取一次 `state.value`，保证 `TextField` 绑定读到的是最新值
///   （Kotlin 的 `updateState` 是同步的，流投递则要等下一轮主线程调度）；
/// - `effects(_:)`：在视图的 `.task` 里收集一次性事件，视图消失时随任务取消；未收集期间事件在 Kotlin Channel 中缓冲；
/// - 销毁（`dispose()` 或 `deinit`）时取消观察并调用 `CoreViewModel.dispose()`。
///
/// 泛型参数与 Kotlin 一致：`S` 状态、`I` 意图（Swift 中是 `any XxxIntent`）、`E` 事件（无事件的 VM 为 `KotlinNothing`）。
@MainActor
@Observable
final class ViewModelHost<S: AnyObject, I: AnyObject, E: AnyObject> {
    /// 当前界面状态（Kotlin data class，`==` 走 Kotlin `equals`）
    private(set) var state: S

    @ObservationIgnored let viewModel: CoreViewModel<S, I, E>
    @ObservationIgnored private var stateTask: Task<Void, Never>?
    @ObservationIgnored private(set) var isDisposed = false

    init(_ viewModel: CoreViewModel<S, I, E>) {
        self.viewModel = viewModel
        self.state = viewModel.state.value
        stateTask = Task { [weak self, viewModel] in
            for await next in viewModel.state {
                guard let self else { return }
                self.state = next
            }
        }
    }

    /// 投递意图；已销毁时忽略
    func send(_ intent: I) {
        guard !isDisposed else { return }
        viewModel.on(intent: intent)
        state = viewModel.state.value
    }

    /// 逐个处理一次性事件，直到调用方任务被取消（通常放在 `.task { await host.effects { ... } }`）
    func effects(_ handle: @MainActor (E) -> Void) async {
        guard !isDisposed else { return }
        for await effect in viewModel.effects {
            handle(effect)
        }
    }

    /// 结束观察并释放 Kotlin 侧协程作用域；可重复调用
    func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        stateTask?.cancel()
        stateTask = nil
        viewModel.dispose()
    }

    isolated deinit {
        dispose()
    }
}
