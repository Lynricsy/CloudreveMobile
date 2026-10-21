import SwiftUI
import OpenCloudreveCore

/// 分享列表工具栏：平时为排序与“选择”；多选时顶部“全选/完成”，底部（Tab 栏隐藏）删除所选
struct ShareListToolbar: ToolbarContent {
    let host: ShareListHost
    let selecting: Bool
    let enterSelection: () -> Void
    let exitSelection: () -> Void
    let deleteSelected: () -> Void

    var body: some ToolbarContent {
        if selecting {
            ToolbarItem(placement: .topBarLeading) {
                Button("全选") {
                    host.send(ShareListIntentSelectAll.shared)
                }
                .accessibilityIdentifier("shares.selectAll")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("完成", action: exitSelection)
                    .accessibilityIdentifier("shares.selectDone")
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("删除", systemImage: "trash", role: .destructive, action: deleteSelected)
                    .disabled(host.state.selection.isEmpty || host.state.deleting)
                    .accessibilityIdentifier("shares.deleteSelected")
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                ShareSortMenu(host: host)
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            ToolbarItem(placement: .topBarTrailing) {
                Button("选择", systemImage: "checkmark.circle", action: enterSelection)
                    .disabled(host.state.shares.isEmpty)
                    .accessibilityIdentifier("shares.select")
            }
        }
    }
}
