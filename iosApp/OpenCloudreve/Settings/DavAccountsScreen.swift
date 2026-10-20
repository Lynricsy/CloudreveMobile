import SwiftUI
import OpenCloudreveCore

/// WebDAV 账号管理
struct DavAccountsScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.davAccountsViewModel(accountId: accountId) }) { host in
            DavAccountsList(accountId: accountId, host: host)
        }
        .navigationTitle("WebDAV")
    }
}
