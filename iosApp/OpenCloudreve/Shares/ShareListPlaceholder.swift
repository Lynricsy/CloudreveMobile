import SwiftUI
import OpenCloudreveCore

/// 没有分享可显示时的整页状态：加载中、错误（可重试）、空列表。放在可滚动容器里以支持下拉刷新。
struct ShareListPlaceholder: View {
    let loading: Bool
    let error: UiError?
    let retry: () -> Void

    var body: some View {
        ScrollView {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("无法加载", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(ErrorText.message(error))
                    } actions: {
                        Button("重试", action: retry)
                            .buttonStyle(.glass)
                    }
                } else if loading {
                    ProgressView()
                        .controlSize(.large)
                } else {
                    ContentUnavailableView(
                        "还没有分享",
                        systemImage: "square.and.arrow.up",
                        description: Text("你创建的分享将显示在这里。在“文件”中长按文件并选择“分享”即可创建。")
                    )
                }
            }
            .containerRelativeFrame(.vertical)
            .frame(maxWidth: .infinity)
        }
    }
}
