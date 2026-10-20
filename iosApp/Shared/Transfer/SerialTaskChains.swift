import Foundation
import Synchronization

/// 按键串行执行异步操作：同一传输任务的回调（分片完成、进度、暂停、接管）依次处理，不同任务之间并发。
final class SerialTaskChains: Sendable {
    private struct Link {
        let token: UUID
        let task: Task<Void, Never>
    }

    private let links = Mutex<[String: Link]>([:])

    /// 把 [operation] 排到 [key] 队尾
    func enqueue(_ key: String, _ operation: @escaping @Sendable () async -> Void) {
        links.withLock { links in
            let previous = links[key]?.task
            let token = UUID()
            let task = Task { [weak self] in
                await previous?.value
                await operation()
                self?.finish(key, token: token)
            }
            links[key] = Link(token: token, task: task)
        }
    }

    /// 等待当前已排队的全部操作（包括执行期间新排入的）结束
    func drain() async {
        while true {
            let pending = links.withLock { $0.values.map(\.task) }
            if pending.isEmpty { return }
            for task in pending {
                await task.value
            }
        }
    }

    private func finish(_ key: String, token: UUID) {
        links.withLock { links in
            if links[key]?.token == token { links[key] = nil }
        }
    }
}
