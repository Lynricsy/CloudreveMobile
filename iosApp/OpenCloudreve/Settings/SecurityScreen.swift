import SwiftUI
import OpenCloudreveCore

/// 账户安全：密码、两步验证、Passkey、已授权应用、个人主页分享可见性
struct SecurityScreen: View {
    let accountId: String
    let settings: SettingsHost

    var body: some View {
        ViewModelScope({ CoreGraph.shared.securityViewModel(accountId: accountId) }) { security in
            SecurityForm(accountId: accountId, security: security, settings: settings)
        }
        .navigationTitle("账户安全")
    }
}
