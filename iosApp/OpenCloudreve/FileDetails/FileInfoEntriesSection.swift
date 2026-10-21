import SwiftUI
import OpenCloudreveCore

/// 信息页入口行：版本（文件）、标签与元数据、直链；返回时回调 `onReturn`（刷新信息）
struct FileInfoEntriesSection: View {
    let accountId: String
    /// 已加载的文件信息（加载前为列表项）
    let file: FileItem
    /// 列表项（能力位缺失时回退）
    let fallback: FileItem
    let versionCount: Int
    let directLinkCount: Int
    let onReturn: () -> Void

    var body: some View {
        let cap = NavigatorCapability.shared
        let showVersions = !file.isFolder && !fallback.isSymbolic && can(cap.VERSION_CONTROL)
        let showMetadata = !inShare && can(cap.UPDATE_METADATA)
        let showDirectLinks = !inShare && file.owned && can(cap.DOWNLOAD_FILE)
        if showVersions || showMetadata || showDirectLinks {
            Section {
                if showVersions {
                    NavigationLink {
                        VersionsScreen(accountId: accountId, file: file)
                            .onDisappear(perform: onReturn)
                    } label: {
                        Label("版本", systemImage: "clock.arrow.circlepath")
                    }
                    .badge(versionCount)
                    .accessibilityIdentifier("info.versions")
                }
                if showMetadata {
                    NavigationLink {
                        MetadataEditScreen(accountId: accountId, uris: [file.path])
                            .onDisappear(perform: onReturn)
                    } label: {
                        Label("标签与元数据", systemImage: "tag")
                    }
                    .accessibilityIdentifier("info.metadata")
                }
                if showDirectLinks {
                    NavigationLink {
                        DirectLinksScreen(accountId: accountId, uris: [file.path])
                            .onDisappear(perform: onReturn)
                    } label: {
                        Label("直链", systemImage: "link")
                    }
                    .badge(directLinkCount)
                    .accessibilityIdentifier("info.directLinks")
                }
            }
        }
    }

    /// 分享文件系统中只读
    private var inShare: Bool {
        CrUri.companion.parseOrNull(s: file.path)?.fs == CrUri.companion.FS_SHARE
    }

    /// 能力位；信息与列表项都没有能力位时按“是否本人文件”判断
    private func can(_ bit: Int32) -> Bool {
        guard let capability = file.capability ?? fallback.capability else { return file.owned }
        return capability.has(bit: bit)
    }
}
