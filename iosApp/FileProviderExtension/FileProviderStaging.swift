import Foundation

/// 扩展的临时文件：App Group 容器 `tmp/FileProvider/<uuid>/`。
/// 主 App 的传输执行器按 `/tmp/FileProvider/` 识别并跳过扩展自己执行的任务。
enum FileProviderStaging {
    static var root: URL {
        AppGroup.containerURL.appending(path: "tmp/FileProvider", directoryHint: .isDirectory)
    }

    /// 新建一个独立目录
    static func makeDirectory() throws -> URL {
        let dir = root.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 删除超过一小时的残留目录（下载交给系统后留下的空目录、被中断的上传副本）
    static func removeStale(olderThan age: TimeInterval = 3600) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        let cutoff = Date().addingTimeInterval(-age)
        for entry in entries {
            let modified = (try? entry.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if modified < cutoff { try? fm.removeItem(at: entry) }
        }
    }
}
