import SwiftUI
import OpenCloudreveCore

struct MeContent: View {
    let host: AccountSwitcherHost
    @State private var showingSwitcher = false
    @State private var confirmingSignOut = false

    var body: some View {
        List {
            if let active = host.state.active {
                Section {
                    AccountCard(
                        nickname: active.account.nickname,
                        email: active.account.email,
                        host: active.account.host,
                        avatarUrl: active.avatarUrl
                    )
                }
            }

            Section {
                Button("切换或添加账户", systemImage: "person.2") { showingSwitcher = true }
                    .accessibilityIdentifier("me.switchAccount")
            }

            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                }
            }

            if let active = host.state.active {
                Section {
                    Button("退出登录", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                        confirmingSignOut = true
                    }
                    .disabled(host.state.signingOutId != nil)
                    .accessibilityIdentifier("me.signOut")
                    .confirmationDialog("退出此账户？", isPresented: $confirmingSignOut, titleVisibility: .visible) {
                        Button("退出登录", role: .destructive) {
                            host.send(AccountSwitcherIntentSignOut(accountId: active.account.id))
                        }
                    } message: {
                        Text("将撤销此设备上的登录凭证，账户数据保留在服务器上。")
                    }
                }
            }
        }
        .sheet(isPresented: $showingSwitcher) {
            AccountSwitcherSheet(host: host)
        }
    }
}
