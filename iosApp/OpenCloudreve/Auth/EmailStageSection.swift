import SwiftUI
import OpenCloudreveCore

/// 第一段：输入邮箱；也可直接网页登录（Passkey）或去注册
struct EmailStageSection: View {
    @Bindable var host: LoginHost

    var body: some View {
        Section {
            TextField("邮箱", text: $host.email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.next)
                .onSubmit(next)
                .accessibilityIdentifier("login.email")
        }

        if let error = host.state.error {
            Section {
                ErrorRow(message: ErrorText.message(error, context: .loginEmail))
            }
        }

        Section {
            PrimaryActionButton(title: "下一步", busy: host.state.submitting, action: next)
                .disabled(!host.state.canContinue)
                .accessibilityIdentifier("login.continue")
        }

        if host.state.enhanced {
            Section {
                SecondaryActionButton(title: "使用 Passkey / 网页登录", systemImage: "person.badge.key") {
                    host.send(LoginIntentStartWebLogin.shared)
                }
                .disabled(host.state.submitting)
                .accessibilityIdentifier("login.webLogin")
            } footer: {
                Text("在系统浏览器中登录此站点，可使用 Passkey 或已保存的网页会话。")
            }
        }

        if host.state.registerEnabled {
            Section {
                Button("注册新账户") { host.send(LoginIntentOpenRegister.shared) }
                    .accessibilityIdentifier("login.register")
            }
        }

        LegalLinksSection(tosUrl: host.state.tosUrl, privacyPolicyUrl: host.state.privacyPolicyUrl)
    }

    private func next() {
        host.send(LoginIntentContinueWithEmail.shared)
    }
}
