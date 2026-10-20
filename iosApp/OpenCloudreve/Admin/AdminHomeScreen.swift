import SwiftUI
import OpenCloudreveCore

/// 管理首页：概览统计与各管理页面入口（仅管理员可见）
struct AdminHomeScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminSummaryViewModel(accountId: accountId) }) { host in
            AdminHomeContent(accountId: accountId, host: host)
        }
        .navigationTitle("管理")
    }
}
