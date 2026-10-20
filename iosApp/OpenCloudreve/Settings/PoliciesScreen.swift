import SwiftUI
import OpenCloudreveCore

/// 可用存储策略（只读）：首位为用户组默认策略，负载均衡策略列出子策略权重
struct PoliciesScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.policiesViewModel(accountId: accountId) }) { host in
            PoliciesList(host: host)
        }
        .navigationTitle("存储策略")
    }
}
