import SwiftUI
import OpenCloudreveCore

/// 管理端节点列表：查看状态与连通测试（增删改在网页后台进行）
struct AdminNodesScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminNodesViewModel(accountId: accountId) }) { host in
            AdminEntityList(
                host: host,
                supportsDelete: false,
                searchFields: [],
                idOf: { $0.id },
                header: { EmptyView() },
                filters: {
                    AdminConditionPicker(
                        host: host,
                        key: AdminConditions.shared.NODE_STATUS,
                        title: "状态",
                        options: ["active", "suspended"].map { AdminOption(value: $0, label: AdminLabels.nodeStatus($0)) }
                    )
                },
                row: { node in
                    AdminNodeRow(node: node, testing: host.state.busyId?.int32Value == node.id)
                        .contextMenu {
                            Button("测试连接", systemImage: "bolt.horizontal") {
                                host.send(AdminListIntentTestNode(id: node.id))
                            }
                        }
                },
                destination: { node in
                    AdminDetailContainer(host: host, id: node.id, idOf: { $0.id }) { detail in
                        AdminNodeDetail(node: detail, testing: host.state.busyId?.int32Value == detail.id) {
                            host.send(AdminListIntentTestNode(id: detail.id))
                        }
                    }
                    .navigationTitle("节点详情")
                }
            )
        }
        .navigationTitle("节点")
    }
}
