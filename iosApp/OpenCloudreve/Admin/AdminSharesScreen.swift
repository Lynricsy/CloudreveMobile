import SwiftUI
import OpenCloudreveCore

/// 管理端分享列表
struct AdminSharesScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminSharesViewModel(accountId: accountId) }) { host in
            AdminEntityList(
                host: host,
                supportsDelete: true,
                searchFields: [
                    AdminSearchField(key: AdminConditions.shared.SHARE_ID, title: "分享 ID"),
                    AdminSearchField(key: AdminConditions.shared.SHARE_USER_ID, title: "用户 ID"),
                    AdminSearchField(key: AdminConditions.shared.SHARE_FILE_ID, title: "文件 ID"),
                ],
                idOf: { $0.id },
                header: { EmptyView() },
                filters: { EmptyView() },
                row: { AdminShareRow(share: $0) },
                destination: { share in
                    AdminDetailContainer(host: host, id: share.id, idOf: { $0.id }) { detail in
                        AdminShareDetail(share: detail, deleting: host.state.deleting) {
                            host.send(AdminListIntentDelete(ids: [KotlinInt(int: detail.id)]))
                        }
                    }
                    .navigationTitle("分享详情")
                }
            )
        }
        .navigationTitle("分享")
    }
}
