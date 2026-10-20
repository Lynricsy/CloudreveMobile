import FileProvider
import OpenCloudreveCore

/// File Provider 条目标识 ↔ Cloudreve 文件。
///
/// 每个账户一个域；条目标识即服务端文件 id（hashid，重命名/移动后不变）。
/// “我的文件”根映射为 `.rootContainer`，回收站根映射为 `.trashContainer`。
enum FileProviderIdentity {
    static let rootURI = "cloudreve://my"
    static let trashURI = "cloudreve://trash"

    /// 系统标识指向的对象
    enum Target: Equatable, Sendable {
        case root
        case trash
        case workingSet
        case file(id: String)
    }

    static func target(of identifier: NSFileProviderItemIdentifier) -> Target {
        switch identifier {
        case .rootContainer: .root
        case .trashContainer: .trash
        case .workingSet: .workingSet
        default: .file(id: identifier.rawValue)
        }
    }

    /// 文件（[fileId]、[uri]）的系统标识；根目录与回收站根返回系统容器标识
    static func identifier(fileId: String, uri: String) -> NSFileProviderItemIdentifier {
        guard let parsed = CrUri.companion.parseOrNull(s: uri) else { return NSFileProviderItemIdentifier(fileId) }
        if parsed.isRoot() {
            if isMyRoot(parsed) { return .rootContainer }
            if parsed.fs == CrUri.companion.FS_TRASH { return .trashContainer }
        }
        return NSFileProviderItemIdentifier(fileId)
    }

    /// 不需要请求就能确定的父项：父目录是“我的文件”根 → `.rootContainer`；回收站内 → `.trashContainer`；
    /// 其它返回 nil（调用方按 [parentURI] 查父目录 id）
    static func knownParent(ofURI uri: String) -> NSFileProviderItemIdentifier? {
        guard let parsed = CrUri.companion.parseOrNull(s: uri) else { return nil }
        if parsed.fs == CrUri.companion.FS_TRASH { return .trashContainer }
        guard let parent = parsed.parent() else { return .rootContainer }
        return isMyRoot(parent) ? .rootContainer : nil
    }

    /// 上级目录 URI；根或无法解析时返回 nil
    static func parentURI(of uri: String) -> String? {
        CrUri.companion.parseOrNull(s: uri)?.parent()?.description
    }

    /// 目录 [parentURI] 下名为 [name] 的子项 URI
    static func childURI(parentURI: String, name: String) -> String {
        FileRepository.companion.childUri(parentUri: parentURI, name: name)
    }

    /// 是否位于回收站
    static func isInTrash(_ uri: String) -> Bool {
        CrUri.companion.parseOrNull(s: uri)?.fs == CrUri.companion.FS_TRASH
    }

    /// 规范化 URI（按共享层规则重新编码），便于比较服务端返回的路径与本地拼出的路径
    static func normalized(_ uri: String) -> String {
        CrUri.companion.parseOrNull(s: uri)?.description ?? uri
    }

    private static func isMyRoot(_ uri: CrUri) -> Bool {
        uri.fs == CrUri.companion.FS_MY && uri.id == nil && uri.isRoot()
    }
}
