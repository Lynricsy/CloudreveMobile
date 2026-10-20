import SwiftUI
import OpenCloudreveCore

/// 包住一个文件条目（列表行或网格单元）的交互：
/// 目录推入子列表、文件打开（预览或详细信息）、多选时切换选中、上下文菜单与（列表模式的）滑动操作。
struct FileItemContainer<Label: View>: View {
    let file: FileItem
    let context: FileActionContext
    let driver: FileActionDriver
    let selecting: Bool
    /// 网格模式：按钮用 plain 样式，多选时点按切换选中（列表模式由 `List(selection:)` 处理）
    let inGrid: Bool
    /// 切换选中；为 nil 时（搜索结果）不提供多选
    let onToggleSelection: (() -> Void)?
    @ViewBuilder let label: () -> Label

    var body: some View {
        let available = context.visibleActions(for: [file])
        item
            .accessibilityIdentifier("file." + file.name)
            .contextMenu {
                if let onToggleSelection {
                    Button("选择", systemImage: "checkmark.circle", action: onToggleSelection)
                }
                FileActionMenuItems(actions: available, pinned: context.isPinned(file.path)) { action in
                    driver.perform(action, targets: [file], in: context)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if !inGrid {
                    if available.contains(.delete) {
                        Button("删除", systemImage: "trash", role: .destructive) {
                            driver.perform(.delete, targets: [file], in: context)
                        }
                    }
                    if available.contains(.rename) {
                        Button("重命名", systemImage: "pencil") {
                            driver.perform(.rename, targets: [file], in: context)
                        }
                        .tint(.orange)
                    }
                }
            }
            .swipeActions(edge: .leading) {
                if !inGrid {
                    if available.contains(.restore) {
                        Button("还原", systemImage: "arrow.uturn.backward") {
                            driver.perform(.restore, targets: [file], in: context)
                        }
                        .tint(.green)
                    } else if available.contains(.info) {
                        Button("详细信息", systemImage: "info.circle") {
                            driver.perform(.info, targets: [file], in: context)
                        }
                        .tint(.blue)
                    }
                }
            }
    }

    @ViewBuilder
    private var item: some View {
        if selecting {
            if inGrid, let onToggleSelection {
                Button(action: onToggleSelection, label: label)
                    .buttonStyle(.plain)
            } else {
                label()
            }
        } else if file.isFolder {
            NavigationLink {
                FileListScreen(accountId: context.accountId, uri: file.isSymbolic ? file.linkedUri : file.path)
            } label: {
                label()
            }
            .buttonStyle(.plain)
        } else {
            Button {
                driver.open(file, in: context)
            } label: {
                label()
            }
            .buttonStyle(.plain)
        }
    }
}
