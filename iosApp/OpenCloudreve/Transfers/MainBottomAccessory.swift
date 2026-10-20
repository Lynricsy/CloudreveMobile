import SwiftUI

/// 主 `TabView` 的底部附件：有进行中的传输时显示迷你栏，点按切到任务 Tab
struct MainBottomAccessory: ViewModifier {
    let accountId: String
    @Binding var selection: MainTab

    @State private var summary = TransferSummaryModel()

    func body(content: Content) -> some View {
        content
            .compatibleBottomAccessory(isEnabled: summary.isActive) {
                TransferMiniBar(
                    uploads: summary.activeUploads,
                    downloads: summary.activeDownloads,
                    progress: summary.progress
                ) {
                    selection = .tasks
                }
            }
            .task(id: accountId) {
                await summary.observe(accountId: accountId)
            }
            #if DEBUG
            .task(id: accountId) {
                await UITestTransferFixture.runIfRequested(accountId: accountId)
            }
            #endif
    }
}

private extension View {
    /// iOS 26.1 起用 `isEnabled` 隐藏整个附件；26.0 只能让内容为空（会留下空的玻璃容器）
    @ViewBuilder
    func compatibleBottomAccessory<Accessory: View>(
        isEnabled: Bool,
        @ViewBuilder content: @escaping () -> Accessory
    ) -> some View {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: isEnabled) { content() }
        } else {
            tabViewBottomAccessory {
                if isEnabled { content() }
            }
        }
    }
}
