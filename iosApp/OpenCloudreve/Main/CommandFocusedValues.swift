import SwiftUI

/// 键盘命令（`AppCommands`）读取的场景级焦点值
extension FocusedValues {
    /// 当前可见的目录列表
    @Entry var fileListCommands: FileListCommandTarget?
    /// 主界面的 Tab 选择（⌘F 切到搜索）
    @Entry var mainTabSelection: Binding<MainTab>?
}
