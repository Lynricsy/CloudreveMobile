import SwiftUI

/// 预览页共用的工具栏动作（由 `ViewerScreen` 提供，见 `ViewerToolbar`）
struct ViewerChrome {
    /// 有多个候选预览器时可切换“打开方式”
    let canSwitch: Bool
    let close: () -> Void
    let switchViewer: () -> Void
    let openExternally: () -> Void
}
