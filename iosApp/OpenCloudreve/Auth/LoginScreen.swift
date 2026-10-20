import SwiftUI
import OpenCloudreveCore

/// 某站点的登录页（两段式：邮箱 → 密码/验证码 → 可能的 2FA；另含注册、找回密码、网页登录）
struct LoginScreen: View {
    let siteUrl: String
    let email: String?
    let onSignedIn: () -> Void

    var body: some View {
        ViewModelScope({
            CoreGraph.shared.loginViewModel(siteUrl: siteUrl, email: email, language: AppLanguage.serverCode)
        }) { host in
            LoginForm(host: host, onSignedIn: onSignedIn)
        }
    }
}
