import SwiftUI

/// 离开当前预览器的动作（都会销毁当前预览器及其未保存的编辑）
enum ViewerLeaveAction: Equatable {
    case close
    case switchViewer
    case openExternally
}

/// 预览页共用的工具栏动作（由 `ViewerScreen` 提供，见 `ViewerToolbar`）
struct ViewerChrome {
    /// 有多个候选预览器时可切换“打开方式”
    let canSwitch: Bool
    let close: () -> Void
    let switchViewer: () -> Void
    let openExternally: () -> Void

    func perform(_ action: ViewerLeaveAction) {
        switch action {
        case .close: close()
        case .switchViewer: switchViewer()
        case .openExternally: openExternally()
        }
    }
}
