import SwiftUI
import OpenCloudreveCore

/// 按登录阶段切换表单分组
struct LoginStageContent: View {
    let host: LoginHost

    var body: some View {
        switch host.state.stage {
        case .email: EmailStageSection(host: host)
        case .password: PasswordStageSection(host: host)
        case .twoFactor: TwoFactorStageSection(host: host)
        case .register: RegisterStageSection(host: host)
        case .forgotPassword: ForgotPasswordStageSection(host: host)
        }
    }
}
