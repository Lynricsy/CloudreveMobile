import SwiftUI
import OpenCloudreveCore

/// 注册新账户
struct RegisterStageSection: View {
    @Bindable var host: LoginHost

    var body: some View {
        Section {
            TextField("邮箱", text: $host.email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("register.email")
            SecureField("密码", text: $host.password)
                .textContentType(.newPassword)
                .accessibilityIdentifier("register.password")
            SecureField("确认密码", text: $host.passwordConfirm)
                .textContentType(.newPassword)
                .accessibilityIdentifier("register.passwordConfirm")
        } footer: {
            if host.state.passwordMismatch {
                Text("两次输入的密码不一致")
                    .foregroundStyle(.red)
            } else {
                Text("密码至少 \(Int(LoginUiState.companion.MIN_PASSWORD_LENGTH)) 位", comment: "注册页密码长度要求")
            }
        }

        CaptchaSection(host: host)

        if let error = host.state.error {
            Section {
                ErrorRow(message: ErrorText.message(error))
            }
        }

        Section {
            PrimaryActionButton(title: "注册", busy: host.state.submitting) {
                host.send(LoginIntentSubmitRegister.shared)
            }
            .disabled(!host.state.canSubmitRegister)
            .accessibilityIdentifier("register.submit")
        }

        LegalLinksSection(tosUrl: host.state.tosUrl, privacyPolicyUrl: host.state.privacyPolicyUrl)
    }
}
