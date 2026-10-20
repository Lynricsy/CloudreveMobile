import SwiftUI
import OpenCloudreveCore

/// “我的”页内容：有当前账户时创建该账户的 `SettingsViewModel`（头像、昵称、用户组、容量、设置入口共用）
struct MeContent: View {
    let host: AccountSwitcherHost

    var body: some View {
        if let active = host.state.active {
            let accountId = active.account.id
            ViewModelScope({ CoreGraph.shared.settingsViewModel(accountId: accountId) }) { settings in
                MeList(host: host, settings: settings)
            }
            .id(accountId)
        } else {
            MeList(host: host, settings: nil)
        }
    }
}
