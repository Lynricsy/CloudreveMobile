import SwiftUI

/// 一个后续阶段的动作入口：返回要呈现的视图；返回 nil 表示动作已直接完成（如加入传输队列），无需界面
struct FileHook {
    let presentation: Presentation
    let content: @MainActor (FileHookContext) -> AnyView?

    /// 界面呈现方式
    enum Presentation {
        /// 以 sheet 呈现（内容自带 NavigationStack 与关闭按钮）
        case sheet
        /// 全屏呈现（预览器）
        case fullScreen
        /// 在当前 NavigationStack 中推入
        case push
    }
}
