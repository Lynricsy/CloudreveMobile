import Foundation

/// 把系统交给扩展的回调对象（completionHandler、枚举观察者、`Progress`）带进异步任务。
///
/// 安全前提：File Provider 的这些回调与观察者可从任意线程调用且只被调用一次/按协议顺序调用，
/// 包装后只在单个任务里使用，不存在并发访问。
struct SystemCallback<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

extension Progress {
    /// 运行 [operation]，返回可取消的进度：系统取消请求时取消任务，完成后进度记为完成
    static func running(_ operation: @escaping @Sendable () async -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let box = SystemCallback(progress)
        let task = Task {
            await operation()
            box.value.completedUnitCount = 1
        }
        progress.cancellationHandler = { task.cancel() }
        return progress
    }
}
