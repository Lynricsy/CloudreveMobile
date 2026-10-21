import SwiftUI

/// 硬件键盘命令（iPad 菜单栏与按住 ⌘ 的快捷键列表中可见）：
/// ⌘N 新建文件夹、⌘R 刷新、⌘F 搜索、⌘⌫ 删除多选中的条目。
/// 全部放在替换 `.newItem` 的同一组里：放在 `CommandGroup(after: .newItem)` 中的命令在 CI 的 iPad 上按键从不生效。
/// 列表命令的目标来自当前场景发布的焦点值；⌘F 经 `AppSession.requestedTab` 交给 `MainTabView` 切换。
/// 删除用 ⌘⌫（与“文件”App、访达一致），不带修饰键的 ⌫ 留给文字输入与系统的键盘焦点导航。
/// 菜单项只按有没有目标启用：目标内部状态变化（列表加载完成、进入多选、选中）不会触发菜单项重新校验，
/// 条件由各操作执行时判断。
struct AppCommands: Commands {
    let session: AppSession
    @FocusedValue(\.fileListCommands) private var fileList

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("新建文件夹") { fileList?.newFolder() }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(fileList == nil)
            Button("刷新") { fileList?.refresh() }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(fileList == nil)
            Button("搜索") { requestSearch() }
                .keyboardShortcut("f", modifiers: .command)
            Button("删除所选项目") { fileList?.deleteSelection() }
                .keyboardShortcut(.delete, modifiers: .command)
                .disabled(fileList == nil)
        }
    }

    /// 已登录（主界面在显示）时切到搜索 Tab
    private func requestSearch() {
        guard session.activeAccount != nil else { return }
        session.requestedTab = .search
    }
}
