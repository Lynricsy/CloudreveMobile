import Foundation

/// 本地文件命名：清理路径分隔符等非法字符、同名时追加序号（`名称 (1).ext`）
enum FileNaming {
    /// 去掉 `/`、`:`、控制字符与首尾空白；结果为空或为 `.`/`..` 时用 [fallback]
    static func sanitize(_ name: String, fallback: String) -> String {
        let cleaned = String(name.unicodeScalars.map { scalar -> Character in
            if scalar == "/" || scalar == ":" || scalar == "\\" || CharacterSet.controlCharacters.contains(scalar) {
                return "_"
            }
            return Character(scalar)
        })
        .trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." {
            return fallback == name ? "file" : sanitize(fallback, fallback: "file")
        }
        return cleaned
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
