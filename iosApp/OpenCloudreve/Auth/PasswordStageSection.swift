import SwiftUI
import OpenCloudreveCore

/// 第二段：输入密码（站点要求时附验证码）
struct PasswordStageSection: View {
    @Bindable var host: LoginHost

    var body: some View {
        Section {
            LabeledContent("邮箱") {
                Text(verbatim: host.state.email)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            SecureField("密码", text: $host.password)
                .textContentType(.password)
                .submitLabel(.go)
                .onSubmit(submit)
                .accessibilityIdentifier("login.password")
        } footer: {
            if !host.state.passwordEnabled {
                Text("该账户未启用密码登录，请使用 Passkey / 网页登录。")
            }
        }

        CaptchaSection(host: host)

        if let error = host.state.error {
            Section {
                ErrorRow(message: ErrorText.message(error))
            }
        }

        Section {
            PrimaryActionButton(title: "登录", busy: host.state.submitting, action: submit)
                .disabled(!host.state.canSubmitPassword)
                .accessibilityIdentifier("login.submit")
        }

        if host.state.enhanced && (host.state.userHasPasskey || !host.state.passwordEnabled) {
            Section {
                SecondaryActionButton(title: "使用 Passkey / 网页登录", systemImage: "person.badge.key") {
                    host.send(LoginIntentStartWebLogin.shared)
                }
                .disabled(host.state.submitting)
                .accessibilityIdentifier("login.webLogin")
            }
        }

        Section {
            Button("忘记密码？") { host.send(LoginIntentOpenForgotPassword.shared) }
                .accessibilityIdentifier("login.forgot")
        }
    }

    private func submit() {
        host.send(LoginIntentSubmitPassword.shared)
    }
}
