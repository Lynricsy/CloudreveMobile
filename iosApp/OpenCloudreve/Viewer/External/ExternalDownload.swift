import Foundation
import Synchronization

/// “用其他应用打开”的下载：调用方任务被取消（关闭预览）时同步取消系统下载任务，
/// 不会继续下载并把用户已放弃的文件移进缓存
enum ExternalDownload {
    /// 下载 [url] 到 [destination]；[onProgress] 在开始时收到系统任务的进度对象。
    /// 失败或取消时不留下目标文件（取消抛 `CancellationError` 或 `URLError.cancelled`）
    @MainActor
    static func download(
        from url: URL,
        to destination: URL,
        session: URLSession = .shared,
        onProgress: (Progress) -> Void
    ) async throws {
        let control = DownloadControl()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let task = session.downloadTask(with: url) { temporary, response, error in
                    if let error {
                        continuation.resume(throwing: error)
                        return
                    }
                    guard let temporary else {
                        continuation.resume(throwing: URLError(.badServerResponse))
                        return
                    }
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                    // 下载刚好完成时被取消：同样放弃
                    guard !control.isCancelled else {
                        try? FileManager.default.removeItem(at: temporary)
                        continuation.resume(throwing: CancellationError())
                        return
                    }
                    guard (200..<300).contains(status) else {
                        try? FileManager.default.removeItem(at: temporary)
                        continuation.resume(throwing: URLError(.badServerResponse))
                        return
                    }
                    do {
                        try FileManager.default.moveItem(at: temporary, to: destination)
                        continuation.resume()
                    } catch {
                        continuation.resume(throwing: error)
                    }
                }
                onProgress(task.progress)
                control.start(task)
            }
        } onCancel: {
            control.cancel()
        }
    }

    /// 启动与取消之间的同步：取消早于启动时任务一创建就取消（系统随后以“已取消”回调完成）
    private final class DownloadControl: Sendable {
        private struct State {
            var task: URLSessionDownloadTask?
            var cancelled = false
        }

        private let state = Mutex(State())

        var isCancelled: Bool {
            state.withLock { $0.cancelled }
        }

        func start(_ task: URLSessionDownloadTask) {
            let cancelled = state.withLock { state in
                state.task = task
                return state.cancelled
            }
            if cancelled { task.cancel() } else { task.resume() }
        }

        func cancel() {
            let task = state.withLock { state in
                state.cancelled = true
                return state.task
            }
            task?.cancel()
        }
    }
}
