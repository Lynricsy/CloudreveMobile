import Foundation

/// 下载落盘位置：App 沙盒 `Documents/Downloads/<账户昵称>/`（Info.plist 开启了文件共享，“文件”App 中可见）。
///
/// 任务表里保存的是绝对路径；App 更新或重装后沙盒容器路径可能变化，使用前用 [resolve] 映射到当前 `Documents/`。
enum DownloadDestination {
    static var documentsURL: URL {
        URL.documentsDirectory
    }

    /// `Documents/Downloads/<账户>/`（不创建）
    static func directory(accountLabel: String, documents: URL = documentsURL) -> URL {
        documents
            .appending(path: "Downloads", directoryHint: .isDirectory)
            .appending(path: FileNaming.sanitize(accountLabel, fallback: "OpenCloudreve"), directoryHint: .isDirectory)
    }

    /// 为 [fileName] 在账户下载目录中分配一个不重名的路径，并创建空占位文件（同时下载多个同名文件时不会撞名）
    static func reserve(fileName: String, accountLabel: String, documents: URL = documentsURL) throws -> URL {
        let fileManager = FileManager.default
        let directory = directory(accountLabel: accountLabel, documents: documents)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = FileNaming.uniqueURL(in: directory, name: FileNaming.sanitize(fileName, fallback: "download"))
        guard fileManager.createFile(atPath: url.path(percentEncoded: false), contents: nil) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: url.path(percentEncoded: false)])
        }
        return url
    }

    /// 把保存的绝对路径映射到当前容器：路径中含 `/Documents/` 时以当前 `Documents/` 为基准重建，否则原样返回
    static func resolve(_ storedPath: String, documents: URL = documentsURL) -> URL {
        let marker = "/Documents/"
        guard let range = storedPath.range(of: marker) else {
            return URL(filePath: storedPath)
        }
        let relative = String(storedPath[range.upperBound...])
        return relative.isEmpty ? documents : documents.appending(path: relative, directoryHint: .notDirectory)
    }
}
