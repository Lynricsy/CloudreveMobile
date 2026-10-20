import SwiftUI
import OpenCloudreveCore

/// 密码：修改密码入口（无密码账户只显示说明）
struct PasswordSection: View {
    let accountId: String
    let security: SecurityHost
    let canChangePassword: Bool
    let siteUrl: String
    let email: String

    var body: some View {
        Section("密码") {
            if canChangePassword {
                NavigationLink {
                    ChangePasswordScreen(security: security, siteUrl: siteUrl, email: email)
                } label: {
                    Label("修改密码", systemImage: "key")
                }
                .accessibilityIdentifier("security.changePassword")
            } else {
                Label("此账户未设置密码（仅通过 Passkey 或第三方登录）", systemImage: "key.slash")
                    .foregroundStyle(.secondary)
            }
        }
    }
}
