import SwiftUI
import OpenCloudreveCore

/// 找回密码：向邮箱发送重置链接
struct ForgotPasswordStageSection: View {
    @Bindable var host: LoginHost

    var body: some View {
        Section {
            TextField("邮箱", text: $host.email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("forgot.email")
        } footer: {
            Text("我们会向该邮箱发送重置密码的链接。")
        }

        CaptchaSection(host: host)

        if let error = host.state.error {
            Section {
                ErrorRow(message: ErrorText.message(error))
            }
        }

        Section {
            PrimaryActionButton(title: "发送重置邮件", busy: host.state.submitting) {
                host.send(LoginIntentSubmitForgotPassword.shared)
            }
            .disabled(!host.state.canSubmitForgot)
            .accessibilityIdentifier("forgot.submit")
        }
    }
}
