import SwiftUI
import OpenCloudreveCore

/// 管理端用户详情与编辑
struct AdminUserEditScreen: View {
    let accountId: String
    let userId: Int32
    /// 保存或删除后通知列表刷新
    let onChanged: () -> Void

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminUserEditViewModel(accountId: accountId, userId: userId) }) { host in
            AdminUserEditForm(accountId: accountId, host: host, onChanged: onChanged)
        }
        .navigationTitle("编辑用户")
        .navigationBarTitleDisplayMode(.inline)
    }
}
