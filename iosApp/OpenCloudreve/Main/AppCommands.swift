import SwiftUI

/// 硬件键盘命令（iPad 菜单栏与按住 ⌘ 的快捷键列表中可见）：
/// ⌘N 新建文件夹、⌘R 刷新、⌘F 搜索、Delete 删除多选中的条目。目标来自当前场景发布的焦点值。
struct AppCommands: Commands {
    @FocusedValue(\.fileListCommands) private var fileList
    @FocusedValue(\.mainTabSelection) private var tabSelection

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("新建文件夹") { fileList?.newFolder() }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(fileList?.canCreateFolder != true)
        }
        CommandGroup(after: .newItem) {
            Button("刷新") { fileList?.refresh() }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(fileList == nil)
            Button("搜索") { tabSelection?.wrappedValue = .search }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(tabSelection == nil)
            Button("删除所选项目") { fileList?.deleteSelection() }
                .keyboardShortcut(.delete, modifiers: [])
                .disabled(fileList?.deletableSelection.isEmpty != false)
        }
    }
}
