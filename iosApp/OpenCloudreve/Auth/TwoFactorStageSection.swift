import SwiftUI
import OpenCloudreveCore

/// 两步验证：输入 6 位 TOTP，输满后 VM 自动提交
struct TwoFactorStageSection: View {
    @Bindable var host: LoginHost
    @FocusState private var focused: Bool

    var body: some View {
        Section {
            TextField("6 位验证码", text: $host.otp)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .font(.title2.monospacedDigit())
                .multilineTextAlignment(.center)
                .focused($focused)
                .accessibilityIdentifier("login.otp")
        } footer: {
            Text("打开身份验证器应用，输入其中显示的 6 位验证码。")
        }
        .onAppear { focused = true }

        if let error = host.state.error {
            Section {
                ErrorRow(message: ErrorText.message(error))
            }
        }

        Section {
            PrimaryActionButton(title: "验证", busy: host.state.submitting) {
                host.send(LoginIntentSubmitOtp.shared)
            }
            .disabled(!host.state.canSubmitOtp)
            .accessibilityIdentifier("login.verifyOtp")
        }
    }
}
