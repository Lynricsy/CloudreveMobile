import SwiftUI
import OpenCloudreveCore

/// 处理 `WorkflowListEffect`（取消/选择文件成功、操作失败）并以提示框呈现。
/// 列表页与推入的详情页各自挂载：`.task` 只在可见页收集，未收集期间事件在 Kotlin Channel 中缓冲。
struct WorkflowListEffectHandling: ViewModifier {
    let host: WorkflowListHost

    @State private var notice: Notice?

    /// 一次提示
    private struct Notice {
        let title: LocalizedStringResource
        var message: String?
    }

    func body(content: Content) -> some View {
        content
            .alert(Text(notice?.title ?? "操作失败"), isPresented: noticeBinding, presenting: notice) { _ in
                Button("好", role: .cancel) {}
            } message: { notice in
                if let message = notice.message {
                    Text(verbatim: message)
                }
            }
            .task {
                await host.effects { effect in
                    switch onEnum(of: effect) {
                    case .canceled:
                        notice = Notice(title: "任务已取消")
                    case .filesSelected:
                        notice = Notice(title: "已更新要下载的文件")
                    case let .failed(failed):
                        notice = Notice(title: "操作失败", message: ErrorText.message(failed.error))
                    }
                }
            }
    }

    private var noticeBinding: Binding<Bool> {
        Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })
    }
}

extension View {
    /// 挂载后台任务列表事件处理
    func workflowListEffects(_ host: WorkflowListHost) -> some View {
        modifier(WorkflowListEffectHandling(host: host))
    }
}
