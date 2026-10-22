import Foundation

/// 本地文件命名：清理路径分隔符等非法字符、同名时追加序号（`名称 (1).ext`）
enum FileNaming {
    /// 去掉 `/`、`:`、控制字符与首尾空白；结果为空或为 `.`/`..` 时用 [fallback]
    static func sanitize(_ name: String, fallback: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleaned = String(trimmed.unicodeScalars.map { scalar -> Character in
            if scalar == "/" || scalar == ":" || scalar == "\\" || CharacterSet.controlCharacters.contains(scalar) {
                return "_"
            }
            return Character(scalar)
        })
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." {
            return fallback == name ? "file" : sanitize(fallback, fallback: "file")
        }
        return cleaned
    }

    /// [directory] 下以服务端/外部给出的 [name] 命名的文件：先清理分隔符等非法字符，
    /// 再确认标准化后的路径仍直接位于 [directory] 内（不允许借 `..`、`/` 写到目录之外）
    static func contained(_ name: String, in directory: URL) throws -> URL {
        let base = directory.standardizedFileURL
        let url = base.appending(path: sanitize(name, fallback: "file"), directoryHint: .notDirectory).standardizedFileURL
        guard trimmedPath(url.deletingLastPathComponent()) == trimmedPath(base) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        return url
    }

    private static func trimmedPath(_ url: URL) -> String {
        var path = url.standardizedFileURL.path(percentEncoded: false)
        while path.count > 1 && path.hasSuffix("/") { path.removeLast() }
        return path
    }

    /// 第 [index] 个候选名：0 为原名，之后为 `名称 (n).ext`
    static func candidate(_ name: String, index: Int) -> String {
        guard index > 0 else { return name }
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        let stem = base.isEmpty ? name : base
        return ext.isEmpty || base.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
    }

    /// [directory] 下第一个不存在的候选文件 URL
    static func uniqueURL(in directory: URL, name: String, fileManager: FileManager = .default) -> URL {
        var index = 0
        while true {
            let url = directory.appending(path: candidate(name, index: index), directoryHint: .notDirectory)
            if !fileManager.fileExists(atPath: url.path(percentEncoded: false)) { return url }
            index += 1
        }
    }
}
