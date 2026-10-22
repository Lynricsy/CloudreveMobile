import Foundation

/// “用其他应用打开”的下载：用 URLSession 的 async API，调用方任务被取消（关闭预览）时网络任务随之取消，
/// 不会继续下载并把用户已放弃的文件移进缓存
enum ExternalDownload {
    /// 下载 [url] 到 [destination]；[onProgress] 在主线程收到系统任务的进度对象。
    /// 失败或取消时不留下目标文件（抛出原错误，取消为 `CancellationError` 或 `URLError.cancelled`）
    static func download(
        from url: URL,
        to destination: URL,
        session: URLSession = .shared,
        onProgress: @escaping @MainActor @Sendable (Progress) -> Void
    ) async throws {
        let observer = TaskCreationObserver { task in
            Task { @MainActor in onProgress(task.progress) }
        }
        let (temporary, response) = try await session.download(from: url, delegate: observer)
        do {
            // 下载刚好完成时被取消：同样放弃
            try Task.checkCancellation()
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            guard (200..<300).contains(status) else { throw URLError(.badServerResponse) }
            try FileManager.default.moveItem(at: temporary, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
    }

    /// 取得 async API 创建的系统任务（用于显示进度）
    private final class TaskCreationObserver: NSObject, URLSessionTaskDelegate, Sendable {
        private let onCreate: @Sendable (URLSessionTask) -> Void

        init(onCreate: @escaping @Sendable (URLSessionTask) -> Void) {
            self.onCreate = onCreate
        }

        func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
            onCreate(task)
        }
    }
}
