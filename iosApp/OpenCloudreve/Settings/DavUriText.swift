import Foundation

/// WebDAV 根目录 URI 的显示文本：`cloudreve://my/a/b` → “我的文件/a/b”，其余原样
enum DavUriText {
    static func display(_ uri: String) -> String {
        let prefix = "cloudreve://my"
        guard uri.hasPrefix(prefix) else { return uri }
        let rest = String(uri.dropFirst(prefix.count))
        let path = (rest.removingPercentEncoding ?? rest).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let root = String(localized: "我的文件")
        return path.isEmpty ? root : root + "/" + path
    }
}
