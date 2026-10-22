import SwiftUI

/// 预览页工具栏：左侧关闭，右侧“打开方式 / 用其他应用打开”
struct ViewerToolbar: ViewModifier {
    let chrome: ViewerChrome
    let title: String
    /// 离开前的拦截：关闭、切换打开方式、用其他应用打开都会先经过它（返回 true 表示已自行处理，
    /// 例如提示未保存的更改，确认后再执行该动作）
    let onLeaveRequest: ((ViewerLeaveAction) -> Bool)?

    func body(content: Content) -> some View {
        content
            .navigationTitle(Text(verbatim: title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭", systemImage: "xmark") { leave(.close) }
                        .accessibilityIdentifier("viewer.close")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("更多", systemImage: "ellipsis") {
                        if chrome.canSwitch {
                            Button("打开方式…", systemImage: "rectangle.stack") { leave(.switchViewer) }
                        }
                        Button("用其他应用打开", systemImage: "square.and.arrow.up") { leave(.openExternally) }
                    }
                    .accessibilityIdentifier("viewer.more")
                }
            }
    }

    private func leave(_ action: ViewerLeaveAction) {
        if onLeaveRequest?(action) != true { chrome.perform(action) }
    }
}

extension View {
    func viewerToolbar(_ chrome: ViewerChrome, title: String, onLeaveRequest: ((ViewerLeaveAction) -> Bool)? = nil) -> some View {
        modifier(ViewerToolbar(chrome: chrome, title: title, onLeaveRequest: onLeaveRequest))
    }
}
