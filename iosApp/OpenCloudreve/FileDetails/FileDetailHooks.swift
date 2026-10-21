import SwiftUI

/// P7 接入文件浏览器的入口（`FileFeatures.installed.versions` / `.metadata`）
@MainActor
enum FileDetailHooks {
    /// 菜单“版本”：单个文件的版本列表；关闭后刷新发起的列表（当前版本可能已变）
    static let versions = FileHook(presentation: .sheet) { context in
        guard let file = context.files.first else { return nil }
        let refresh = context.refresh
        return AnyView(
            VersionsSheet(accountId: context.accountId, file: file)
                .onDisappear { refresh() }
        )
    }

    /// 菜单“标签与元数据”：1..n 个文件；关闭后刷新发起的列表
    static let metadata = FileHook(presentation: .sheet) { context in
        guard !context.files.isEmpty else { return nil }
        let refresh = context.refresh
        return AnyView(
            MetadataEditSheet(accountId: context.accountId, uris: context.files.map(\.path))
                .onDisappear { refresh() }
        )
    }
}
