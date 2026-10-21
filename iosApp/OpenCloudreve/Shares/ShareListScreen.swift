import SwiftUI
import OpenCloudreveCore

/// 我的分享列表页（不自带导航栈）
struct ShareListScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.shareListViewModel(accountId: accountId) }) { host in
            ShareListContent(accountId: accountId, host: host)
        }
        .navigationTitle("分享")
    }
}
