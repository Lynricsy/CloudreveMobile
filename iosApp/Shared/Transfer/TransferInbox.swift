import Foundation

/// App Group 容器中的 `Inbox/`：照片、拍照、分享扩展收到的附件先复制到这里，再以绝对路径作为上传 `sourceRef`。
/// 每批文件一个 `Inbox/<uuid>/` 子目录；上传完成或取消后删除副本（目录空了一并删除）。
enum TransferInbox {
    static var root: URL {
        AppGroup.containerURL.appending(path: "Inbox", directoryHint: .isDirectory)
    }

    /// 新建一个批次目录 `Inbox/<uuid>/`
    static func makeBatchDirectory() throws -> URL {
        let directory = root.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// 把 [source] 复制到 [directory]，文件名取 [name]（清理非法字符，重名时加序号）
    static func copy(_ source: URL, into directory: URL, name: String) throws -> URL {
        let target = FileNaming.uniqueURL(in: directory, name: FileNaming.sanitize(name, fallback: source.lastPathComponent))
        try FileManager.default.copyItem(at: source, to: target)
        return target
    }

    /// 把数据写成 [directory] 下的文件
    static func write(_ data: Data, into directory: URL, name: String) throws -> URL {
        let target = FileNaming.uniqueURL(in: directory, name: FileNaming.sanitize(name, fallback: "file"))
        try data.write(to: target, options: .atomic)
        return target
    }

    /// `sourceRef` 是否指向收件箱中的副本
    static func contains(sourceRef: String) -> Bool {
        guard let path = localPath(sourceRef) else { return false }
        return path.hasPrefix(root.path(percentEncoded: false))
    }

    /// 删除收件箱副本；不是收件箱文件时不做任何事
    static func discard(sourceRef: String) {
        guard contains(sourceRef: sourceRef), let path = localPath(sourceRef) else { return }
        let fileManager = FileManager.default
        try? fileManager.removeItem(atPath: path)
        let parent = (path as NSString).deletingLastPathComponent
        if parent != root.path(percentEncoded: false),
           (try? fileManager.contentsOfDirectory(atPath: parent))?.isEmpty == true {
            try? fileManager.removeItem(atPath: parent)
        }
    }

    /// `sourceRef` 的本地路径（绝对路径或 file URL；书签返回 nil）
    private static func localPath(_ sourceRef: String) -> String? {
        if sourceRef.hasPrefix("/") { return sourceRef }
        if sourceRef.hasPrefix("file://") { return URL(string: sourceRef)?.path(percentEncoded: false) }
        return nil
    }
}
