import SwiftUI
import OpenCloudreveCore

/// 受密码保护的分享：输入密码后解锁
struct ShareVisitPasswordForm: View {
    let share: Share?
    @Binding var password: String
    let incorrect: Bool
    let submitting: Bool
    let submit: () -> Void

    var body: some View {
        Form {
            Section {
                if let owner = share?.owner {
                    Text("\(owner.nickname) 分享了受密码保护的内容", comment: "参数为分享者昵称")
                } else {
                    Text("此分享受密码保护")
                }
                SecureField("分享密码", text: $password)
                    .textContentType(.password)
                    .onSubmit(submit)
                    .accessibilityIdentifier("shareVisit.password")
                if incorrect {
                    ErrorRow(message: String(localized: "密码不正确"))
                }
            }
            Section {
                PrimaryActionButton(title: "解锁", busy: submitting, action: submit)
                    .disabled(password.isEmpty || submitting)
            }
            .listRowBackground(Color.clear)
        }
    }
}
