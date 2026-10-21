import SwiftUI
import OpenCloudreveCore

/// 包住一行分享的交互：点按编辑、多选时由 `List(selection:)` 切换选中、上下文菜单与滑动操作
struct ShareRowItem: View {
    let share: Share
    let selecting: Bool
    let onToggleSelection: () -> Void
    let perform: (ShareRowAction) -> Void

    var body: some View {
        let hasLink = !share.url.isEmpty
        item
            .accessibilityIdentifier("share." + ShareFormat.title(share))
            .contextMenu {
                if !selecting {
                    Button("选择", systemImage: "checkmark.circle", action: onToggleSelection)
                }
                ShareRowMenuItems(hasLink: hasLink, perform: perform)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if !selecting {
                    Button("删除", systemImage: "trash", role: .destructive) { perform(.delete) }
                    Button("编辑", systemImage: "pencil") { perform(.edit) }
                        .tint(.orange)
                }
            }
            .swipeActions(edge: .leading) {
                if !selecting && hasLink {
                    Button("复制链接", systemImage: "doc.on.doc") { perform(.copy) }
                        .tint(.blue)
                }
            }
    }

    @ViewBuilder
    private var item: some View {
        if selecting {
            ShareRow(share: share)
        } else {
            Button {
                perform(.edit)
            } label: {
                ShareRow(share: share)
            }
            .buttonStyle(.plain)
        }
    }
}
