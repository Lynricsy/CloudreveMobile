import SwiftUI
import OpenCloudreveCore

/// “我的”页列表：账户卡、头像/昵称/用户组、容量、设置与管理入口、账户切换、退出登录
struct MeList: View {
    let host: AccountSwitcherHost
    /// 当前账户的设置 VM（无当前账户时为 nil）
    let settings: SettingsHost?
    @State private var showingSwitcher = false
    @State private var confirmingSignOut = false
    @State private var failure: String?

    var body: some View {
        List {
            if let active = host.state.active {
                Section {
                    AccountCard(
                        nickname: displayNickname(fallback: active.account.nickname),
                        email: active.account.email,
                        host: active.account.host,
                        avatarUrl: settings?.state.avatarUrl ?? active.avatarUrl
                    )
                    if let settings {
                        MeProfileRows(settings: settings)
                    }
                }

                if let settings {
                    MeCapacitySection(
                        capacity: settings.state.capacity,
                        usedFraction: settings.state.capacityUsedFraction,
                        overQuota: settings.state.overQuota
                    )
                    MeEntriesSection(
                        accountId: active.account.id,
                        settings: settings,
                        isAdmin: settings.state.user?.isAdmin ?? false
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
        .refreshable {
            settings?.send(SettingsIntentRefresh.shared)
        }
        .sheet(isPresented: $showingSwitcher) {
            AccountSwitcherSheet(host: host)
        }
        .task {
            guard let settings else { return }
            await settings.effects { effect in
                if case let .failed(failed) = onEnum(of: effect) {
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert(message: $failure)
    }

    /// 服务端返回的昵称优先（修改昵称后立即更新），未加载时用本地账户记录
    private func displayNickname(fallback: String) -> String {
        guard let nickname = settings?.state.nickname, !nickname.isEmpty else { return fallback }
        return nickname
    }
}
