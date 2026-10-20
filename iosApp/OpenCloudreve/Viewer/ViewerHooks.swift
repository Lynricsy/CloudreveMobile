import SwiftUI

/// P6 接入文件浏览器的入口（`FileFeatures.installed.preview`）
@MainActor
enum ViewerHooks {
    /// 点按文件 / 菜单“打开”：全屏预览
    static let preview = FileHook(presentation: .fullScreen) { context in
        guard let file = context.files.first else { return nil }
        return AnyView(ViewerScreen(accountId: context.accountId, file: file))
    }
}
