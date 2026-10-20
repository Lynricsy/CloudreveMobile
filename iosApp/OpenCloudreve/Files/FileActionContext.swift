import SwiftUI
import OpenCloudreveCore

/// 执行文件动作时需要的上下文（由列表/搜索视图在 body 中构造，轻量）
@MainActor
struct FileActionContext {
    let accountId: String
    /// 动作发起处的目录（或搜索）URI
    let listingUri: String
    let user: User?
    let viewerResolver: ViewerResolver?
    let storagePolicy: StoragePolicy?
    /// 来自搜索结果（提供“前往所在目录”）
    let inSearch: Bool
    let actions: FileActionHost
    let refresh: @MainActor () -> Void

    func isPinned(_ path: String) -> Bool {
        user?.pined.contains { $0.uri == path } == true
    }

    func isPinLocked(_ path: String) -> Bool {
        user?.pined.contains { $0.uri == path && $0.locked } == true
    }

    /// 文件是否有可用的预览器
    func hasViewer(_ file: FileItem) -> Bool {
        guard !file.isFolder, let viewerResolver else { return false }
        return !viewerResolver.candidatesFor(file: file).isEmpty
    }

    /// 菜单中可见的动作（权限/能力规则 + 后续阶段是否接入 + 锁定的固定项）
    func visibleActions(for targets: [FileItem]) -> Set<FileAction> {
        let hasViewer = targets.count == 1 && hasViewer(targets[0])
        var actions = FileFeatures.installed.filter(
            FileActionPolicy.available(targets: targets, user: user, hasViewer: hasViewer, inSearch: inSearch)
        )
        if targets.count == 1, isPinLocked(targets[0].path) { actions.remove(.pin) }
        return actions
    }

    func hookContext(files: [FileItem], folderUri: String) -> FileHookContext {
        FileHookContext(
            accountId: accountId,
            listingUri: listingUri,
            files: files,
            folderUri: folderUri,
            storagePolicy: storagePolicy,
            refresh: refresh
        )
    }
}
