import SwiftUI
import OpenCloudreveCore

/// 未登录（或当前账户会话失效）时的根界面：`NavigationStack` 服务器地址页 → 登录页。
/// 登录成功后根视图会因当前账户变化自动切到主界面，这里无需处理。
struct AuthFlowView: View {
    /// 根页面；会话失效时直接从该站点的登录页开始
    let root: AuthRoute
    @State private var path: [AuthRoute] = []
    @State private var showingAccounts = false

    var body: some View {
        NavigationStack(path: $path) {
            AuthDestination(route: root, path: $path, onSignedIn: {})
                .toolbar {
                    if isRelogin {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("账户", systemImage: "person.2") { showingAccounts = true }
                                .accessibilityIdentifier("auth.accounts")
                        }
                    }
                }
                .navigationDestination(for: AuthRoute.self) { route in
                    AuthDestination(route: route, path: $path, onSignedIn: {})
                }
        }
        .sheet(isPresented: $showingAccounts) {
            ViewModelScope({ CoreGraph.shared.accountSwitcherViewModel() }) { host in
                AccountSwitcherSheet(host: host)
            }
        }
    }

    /// 会话失效后重新登录：提供账户切换入口（可改用其他账户或登出）
    private var isRelogin: Bool {
        if case .login = root { true } else { false }
    }
}