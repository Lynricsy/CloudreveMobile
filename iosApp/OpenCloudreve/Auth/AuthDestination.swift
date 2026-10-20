import SwiftUI

/// 把 `AuthRoute` 渲染成页面；服务器探测成功后在同一导航栈里推入登录页
struct AuthDestination: View {
    let route: AuthRoute
    @Binding var path: [AuthRoute]
    /// 登录成功（账户已保存并激活）
    let onSignedIn: () -> Void

    var body: some View {
        switch route {
        case .server:
            ServerSetupScreen { siteUrl in
                path.append(.login(siteUrl: siteUrl, email: nil))
            }
        case let .login(siteUrl, email):
            LoginScreen(siteUrl: siteUrl, email: email, onSignedIn: onSignedIn)
        }
    }
}
