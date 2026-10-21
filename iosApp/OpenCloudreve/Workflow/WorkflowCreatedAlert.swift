import SwiftUI

/// 任务创建成功的提示框；点“好”（`workflow.created.ok`）后回调（通常是通知发起方并关闭表单）
struct WorkflowCreatedAlert: ViewModifier {
    let title: LocalizedStringKey
    @Binding var isPresented: Bool
    let message: String
    let onDone: @MainActor () -> Void

    func body(content: Content) -> some View {
        content.alert(title, isPresented: $isPresented) {
            Button("好", action: onDone)
                .accessibilityIdentifier("workflow.created.ok")
        } message: {
            Text(verbatim: message)
        }
    }
}

extension View {
    /// 挂载任务创建成功提示
    func workflowCreatedAlert(
        _ title: LocalizedStringKey,
        isPresented: Binding<Bool>,
        message: String,
        onDone: @escaping @MainActor () -> Void
    ) -> some View {
        modifier(WorkflowCreatedAlert(title: title, isPresented: isPresented, message: message, onDone: onDone))
    }
}
