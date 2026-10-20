import Foundation
import OpenCloudreveCore

/// 打开某个预览器所需的参数（各预览页在 `ViewModelScope` 里据此创建 Kotlin VM）
struct ViewerRequest {
    let accountId: String
    let file: FileItem
    /// 指定版本实体（预览历史版本）；nil 为当前版本
    let version: String?
    let viewerId: String

    /// 同目录文件交给 VM 自行加载（父目录最多 10 页）
    var params: ViewerParams {
        ViewerParams(accountId: accountId, file: file, version: version, siblings: nil, contextHint: nil, viewerId: viewerId)
    }

    /// 文件所在目录（`cloudreve://my/a/b.md` → `cloudreve://my/a`）
    var parentUri: String { ViewerRequest.parentUri(of: file.path) }

    static func parentUri(of uri: String) -> String {
        guard let schemeEnd = uri.range(of: "://") else { return uri }
        let rest = uri[schemeEnd.upperBound...]
        guard let slash = rest.lastIndex(of: "/") else { return uri }
        return String(uri[..<slash])
    }
}
