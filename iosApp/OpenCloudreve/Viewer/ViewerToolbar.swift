import SwiftUI

/// 预览页工具栏：左侧关闭，右侧“打开方式 / 用其他应用打开”
struct ViewerToolbar: ViewModifier {
    let chrome: ViewerChrome
    let title: String
    /// 关闭前的拦截（返回 true 表示已自行处理，例如提示未保存的更改）
    let onCloseRequest: (() -> Bool)?

    func body(content: Content) -> some View {
        content
            .navigationTitle(Text(verbatim: title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭", systemImage: "xmark") {
                        if onCloseRequest?() != true { chrome.close() }
                    }
                    .accessibilityIdentifier("viewer.close")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("更多", systemImage: "ellipsis") {
                        if chrome.canSwitch {
                            Button("打开方式…", systemImage: "rectangle.stack") { chrome.switchViewer() }
                        }
                        Button("用其他应用打开", systemImage: "square.and.arrow.up") { chrome.openExternally() }
                    }
                    .accessibilityIdentifier("viewer.more")
                }
            }
    }
}

extension View {
    func viewerToolbar(_ chrome: ViewerChrome, title: String, onCloseRequest: (() -> Bool)? = nil) -> some View {
        modifier(ViewerToolbar(chrome: chrome, title: title, onCloseRequest: onCloseRequest))
    }
}
