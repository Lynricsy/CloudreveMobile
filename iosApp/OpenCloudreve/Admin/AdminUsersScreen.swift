import SwiftUI
import OpenCloudreveCore

/// 管理端用户列表
struct AdminUsersScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminUsersViewModel(accountId: accountId) }) { host in
            AdminUsersContent(accountId: accountId, host: host)
        }
        .navigationTitle("用户")
    }
}
