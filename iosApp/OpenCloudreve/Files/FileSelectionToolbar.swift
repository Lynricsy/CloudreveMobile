import SwiftUI
import OpenCloudreveCore

/// 多选模式的工具栏：顶部“全选/完成”，底部（Tab 栏隐藏）移动、复制、删除与“更多”
struct FileSelectionToolbar: ToolbarContent {
    let selected: [FileItem]
    let context: FileActionContext
    let driver: FileActionDriver
    /// 批量上限（`max_batch_size`，0 表示未知/不限）
    let maxBatchSize: Int
    let selectAll: () -> Void
    let done: () -> Void

    var body: some ToolbarContent {
        let overLimit = maxBatchSize > 0 && selected.count > maxBatchSize
        let available = overLimit ? [] : context.visibleActions(for: selected)
        // 下载由后续阶段接入；未接入时不占位
        let primary: [FileAction] = FileFeatures.installed.download == nil ? [.move, .copy, .delete] : [.download, .move, .copy, .delete]
        ToolbarItem(placement: .topBarLeading) {
            Button("全选", action: selectAll)
                .accessibilityIdentifier("files.selectAll")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button("完成", action: done)
                .accessibilityIdentifier("files.selectDone")
        }
        ToolbarItemGroup(placement: .bottomBar) {
            ForEach(primary, id: \.self) { action in
                Button(role: action.isDestructive ? .destructive : nil) {
                    driver.perform(action, targets: selected, in: context)
                } label: {
                    Label(action.title(), systemImage: action.systemImage)
                }
                .disabled(!available.contains(action))
                .accessibilityIdentifier("files.selection." + String(describing: action))
            }
        }
        ToolbarSpacer(.flexible, placement: .bottomBar)
        ToolbarItem(placement: .bottomBar) {
            let rest = available.subtracting(primary)
            Menu {
                if overLimit {
                    Text("一次最多操作 \(maxBatchSize) 项", comment: "多选超过批量上限的提示")
                }
                FileActionMenuItems(actions: rest, pinned: selected.count == 1 && context.isPinned(selected[0].path)) { action in
                    driver.perform(action, targets: selected, in: context)
                }
            } label: {
                Label("更多", systemImage: "ellipsis")
            }
            .disabled(selected.isEmpty || !overLimit && rest.isEmpty)
            .accessibilityIdentifier("files.selection.more")
        }
    }
}
