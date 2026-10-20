import SwiftUI
import OpenCloudreveCore

/// 管理端文件列表（仅普通文件）
struct AdminFilesScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminFilesViewModel(accountId: accountId) }) { host in
            AdminEntityList(
                host: host,
                supportsDelete: true,
                searchFields: [
                    AdminSearchField(key: AdminConditions.shared.FILE_NAME, title: "文件名"),
                    AdminSearchField(key: AdminConditions.shared.FILE_USER, title: "用户 ID"),
                ],
                idOf: { $0.id },
                header: { EmptyView() },
                filters: {
                    AdminConditionToggle(host: host, key: AdminConditions.shared.FILE_SHARED, title: "仅已分享")
                    AdminConditionToggle(host: host, key: AdminConditions.shared.FILE_DIRECT_LINK, title: "仅有直链")
                },
                row: { AdminFileRow(file: $0) },
                destination: { file in
                    AdminDetailContainer(host: host, id: file.id, idOf: { $0.id }) { detail in
                        AdminFileDetail(
                            file: detail,
                            fetchingUrl: host.state.busyId?.int32Value == detail.id,
                            deleting: host.state.deleting,
                            fetchUrl: { host.send(AdminListIntentFetchFileUrl(id: detail.id)) },
                            delete: { host.send(AdminListIntentDelete(ids: [KotlinInt(int: detail.id)])) }
                        )
                    }
                    .navigationTitle("文件详情")
                }
            )
        }
        .navigationTitle("文件")
    }
}
