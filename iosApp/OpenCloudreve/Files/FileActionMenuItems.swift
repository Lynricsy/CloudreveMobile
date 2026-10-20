import SwiftUI

/// 菜单里的动作按钮（上下文菜单、多选“更多”菜单共用），按固定顺序分组
struct FileActionMenuItems: View {
    let actions: Set<FileAction>
    /// 单个目标且已固定：固定动作显示为“取消固定”
    let pinned: Bool
    let perform: (FileAction) -> Void

    private static let groups: [[FileAction]] = [
        [.open, .download, .share],
        [.rename, .move, .copy, .pin, .goToSharedLink, .goToParent],
        [.info, .versions, .metadata, .directLink],
        [.createArchive, .extractArchive, .remoteDownload, .relocate, .mountPolicy, .resetThumbnail, .disableThumbnail],
        [.restore, .delete],
    ]

    var body: some View {
        ForEach(Self.groups.indices, id: \.self) { index in
            let group = Self.groups[index].filter(actions.contains)
            if !group.isEmpty {
                Section {
                    ForEach(group, id: \.self) { action in
                        Button(role: action.isDestructive ? .destructive : nil) {
                            perform(action)
                        } label: {
                            Label(action.title(pinned: pinned), systemImage: action.systemImage)
                        }
                    }
                }
            }
        }
    }
}
