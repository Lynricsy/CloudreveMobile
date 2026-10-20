import SwiftUI
import OpenCloudreveCore

/// 修改密码。成功后服务端吊销全部登录凭证，本机随之登出，根视图进入该站点登录页（预填邮箱）。
struct ChangePasswordScreen: View {
    let security: SecurityHost
    let siteUrl: String
    let email: String
    @Environment(AppSession.self) private var session
    @State private var current = ""
    @State private var newPassword = ""
    @State private var confirm = ""
    @State private var failure: String?

    var body: some View {
        Form {
            Section {
                SecureField("当前密码", text: $current)
                    .textContentType(.password)
                    .accessibilityIdentifier("password.current")
            }
            Section {
                SecureField("新密码", text: $newPassword)
                    .textContentType(.newPassword)
                    .accessibilityIdentifier("password.new")
                SecureField("确认新密码", text: $confirm)
                    .textContentType(.newPassword)
                    .accessibilityIdentifier("password.confirm")
            } footer: {
                if !confirm.isEmpty && confirm != newPassword {
                    Text("两次输入的密码不一致")
                        .foregroundStyle(.red)
                } else {
                    Text("新密码至少 6 位。修改后需要重新登录。")
                }
            }
            Section {
                PrimaryActionButton(title: "修改密码", busy: security.state.busy == .changePassword) {
                    session.reloginHint = ReloginHint(siteUrl: siteUrl, email: email)
                    security.send(SecurityIntentChangePassword(currentPassword: current, newPassword: newPassword))
                }
                .disabled(!canSubmit)
                .accessibilityIdentifier("password.submit")
            }
        }
        .navigationTitle("修改密码")
        .task {
            await security.effects { effect in
                if case let .failed(failed) = onEnum(of: effect) {
                    session.reloginHint = nil
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert("修改失败", message: $failure)
    }

    private var canSubmit: Bool {
        current.count >= 4 && newPassword.count >= 6 && newPassword == confirm && security.state.busy == nil
    }
}
