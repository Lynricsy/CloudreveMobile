import SwiftUI

/// 一次性操作失败提示：`message` 非 nil 时弹出，关闭后清空
struct ErrorMessageAlert: ViewModifier {
    let title: LocalizedStringKey
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.alert(title, isPresented: isPresented) {
            Button("好", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }

    private var isPresented: Binding<Bool> {
        Binding(get: { message != nil }, set: { if !$0 { message = nil } })
    }
}

extension View {
    /// 绑定一条可空的错误文案：非 nil 时弹出提示
    func errorMessageAlert(_ title: LocalizedStringKey = "操作失败", message: Binding<String?>) -> some View {
        modifier(ErrorMessageAlert(title: title, message: message))
    }
}
