import SwiftUI

/// 预览页的加载 / 错误占位
struct ViewerStatusView: View {
    enum Status {
        case loading
        case failed(String)
    }

    let status: Status
    var retry: (() -> Void)?

    var body: some View {
        switch status {
        case .loading:
            ProgressView()
                .controlSize(.large)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .failed(message):
            ContentUnavailableView {
                Label("无法打开", systemImage: "exclamationmark.triangle")
            } description: {
                Text(verbatim: message)
            } actions: {
                if let retry {
                    Button("重试", action: retry)
                        .buttonStyle(.glass)
                }
            }
            .accessibilityIdentifier("viewer.error")
        }
    }
}
