import SwiftUI
import OpenCloudreveCore

/// 分享编辑 sheet 的内容：加载中 / 加载失败（可重试）/ 表单 / 保存后的链接结果
struct ShareEditContent: View {
    let host: ShareEditHost
    let onSaved: @MainActor () -> Void

    /// 提交失败的提示（下次提交时清除）
    @State private var failure: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = host.state
        Group {
            if let result = state.result {
                ShareResultView(result: result, name: state.name)
            } else if let error = state.loadError {
                ContentUnavailableView {
                    Label("无法加载", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(ErrorText.message(error))
                } actions: {
                    Button("重试") { host.send(ShareEditIntentReload.shared) }
                        .buttonStyle(.glass)
                }
            } else if state.loading {
                ProgressView()
                    .controlSize(.large)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ShareEditForm(host: host, failure: failure) {
                    failure = nil
                    host.send(ShareEditIntentSubmit.shared)
                }
            }
        }
        .navigationTitle(title(state))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if state.result == nil {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .interactiveDismissDisabled(state.submitting)
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .saved:
                    // 结果页由 `state.result` 驱动；这里只通知发起方刷新
                    onSaved()
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
    }

    private func title(_ state: ShareEditState) -> LocalizedStringKey {
        if state.result != nil { return "分享链接" }
        return state.isEditing ? "编辑分享" : "新建分享"
    }
}
