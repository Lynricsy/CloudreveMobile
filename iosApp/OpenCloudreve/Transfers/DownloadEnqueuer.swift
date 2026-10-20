import Foundation
import OpenCloudreveCore

/// 把选中的文件加入下载队列：目标为 `Documents/Downloads/<账户昵称>/<文件名>`（重名追加序号），目录跳过
enum DownloadEnqueuer {
    /// 一个待下载文件的可发送字段
    struct Target: Sendable {
        let uri: String
        let name: String
        let size: Int64
    }

    @MainActor
    static func enqueue(accountId: String, files: [FileItem]) {
        let targets = files.filter { !$0.isFolder }.map { Target(uri: $0.linkedUri, name: $0.name, size: $0.size) }
        guard !targets.isEmpty else { return }
        let account = CoreGraph.shared.accounts().account(id: accountId)
        let label = [account?.nickname, account?.email].compactMap { $0 }.first { !$0.isEmpty } ?? "OpenCloudreve"
        Task {
            await enqueue(accountId: accountId, label: label, targets: targets)
        }
    }

    nonisolated private static func enqueue(accountId: String, label: String, targets: [Target]) async {
        let manager = CoreGraph.shared.transferManager()
        for target in targets {
            guard let destination = try? DownloadDestination.reserve(fileName: target.name, accountLabel: label) else { continue }
            do {
                _ = try await manager.enqueueDownload(
                    accountId: accountId,
                    fileUri: target.uri,
                    displayName: target.name,
                    size: target.size,
                    destPath: destination.path(percentEncoded: false)
                )
            } catch {
                try? FileManager.default.removeItem(at: destination)
            }
        }
    }
}
