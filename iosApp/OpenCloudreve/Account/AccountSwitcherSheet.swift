import SwiftUI
import OpenCloudreveCore

/// 账户切换器：切换、登出、添加账户（在同一导航栈里走服务器地址页 → 登录页）
struct AccountSwitcherSheet: View {
    let host: AccountSwitcherHost
    @Environment(\.dismiss) private var dismiss
    @State private var path: [AuthRoute] = []
    @State private var pendingSignOut: String?
    @State private var confirmingSignOut = false

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section("账户") {
                    ForEach(host.state.accounts, id: \.account.id) { item in
                        AccountSwitcherRow(
                            nickname: item.account.nickname,
                            email: item.account.email,
                            host: item.account.host,
                            avatarUrl: item.avatarUrl,
                            isActive: item.isActive,
                            sessionExpired: item.account.sessionExpired,
                            signingOut: host.state.signingOutId == item.account.id,
                            select: { select(item) },
                            signOut: {
                                pendingSignOut = item.account.id
                                confirmingSignOut = true
                            }
                        )
                    }
                }

                Section {
                    Button("添加账户", systemImage: "plus") { path.append(.server) }
                        .accessibilityIdentifier("switcher.add")
                }

                if let error = host.state.error {
                    Section {
                        ErrorRow(message: ErrorText.message(error))
                    }
                }
            }
            .accessibilityIdentifier("switcher.list")
            .navigationTitle("账户")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成", systemImage: "checkmark") { dismiss() }
                        .accessibilityIdentifier("switcher.done")
                }
            }
            .confirmationDialog("退出此账户？", isPresented: $confirmingSignOut, titleVisibility: .visible) {
                Button("退出登录", role: .destructive) {
                    if let id = pendingSignOut {
                        host.send(AccountSwitcherIntentSignOut(accountId: id))
                    }
                    pendingSignOut = nil
                }
            } message: {
                Text("将撤销此设备上的登录凭证，账户数据保留在服务器上。")
            }
            .navigationDestination(for: AuthRoute.self) { route in
                AuthDestination(route: route, path: $path, onSignedIn: { dismiss() })
            }
        }
    }

    /// 切换到所选账户；会话失效的账户切换后根视图会进入该站点的登录页
    private func select(_ item: AccountItem) {
        if !item.isActive {
            host.send(AccountSwitcherIntentSwitchTo(accountId: item.account.id))
        }
        dismiss()
    }
}
