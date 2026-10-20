import SwiftUI
import OpenCloudreveCore

/// 节点详情：基本信息、能力、关联存储策略、连通测试
struct AdminNodeDetail: View {
    let node: AdminNode
    let testing: Bool
    let test: () -> Void

    /// 节点能力位（`types.NodeCapability`）
    private static let capabilities: [(bit: Int32, title: LocalizedStringKey)] = [
        (1, "创建压缩包"),
        (2, "解压缩"),
        (3, "离线下载"),
    ]

    var body: some View {
        Form {
            Section("信息") {
                LabeledContent("名称") { Text(verbatim: node.name) }
                LabeledContent("类型") { Text(verbatim: AdminLabels.nodeType(node.type)) }
                LabeledContent("状态") { Text(verbatim: AdminLabels.nodeStatus(node.status)) }
                if !node.server.isEmpty {
                    LabeledContent("地址") {
                        Text(verbatim: node.server)
                            .textSelection(.enabled)
                    }
                }
                LabeledContent("权重") { Text(Int(node.weight), format: .number) }
                if let updated = SettingsFormat.dateTime(node.updatedAt) {
                    LabeledContent("更新时间") { Text(verbatim: updated) }
                }
            }

            Section("能力") {
                ForEach(Self.capabilities, id: \.bit) { capability in
                    LabeledContent(capability.title) {
                        Image(systemName: node.capabilities?.has(bit: capability.bit) == true ? "checkmark.circle.fill" : "minus.circle")
                            .foregroundStyle(node.capabilities?.has(bit: capability.bit) == true ? .green : .secondary)
                            .accessibilityLabel(Text(node.capabilities?.has(bit: capability.bit) == true ? "支持" : "不支持"))
                    }
                }
            }

            if !node.edges.storagePolicy.isEmpty {
                Section("存储策略") {
                    ForEach(node.edges.storagePolicy, id: \.id) { policy in
                        LabeledContent {
                            Text(verbatim: PolicyTypeName.name(policy.type))
                        } label: {
                            Text(verbatim: policy.name)
                        }
                    }
                }
            }

            Section {
                Button(action: test) {
                    HStack {
                        Label("测试连接", systemImage: "bolt.horizontal")
                        Spacer()
                        if testing {
                            ProgressView()
                        }
                    }
                }
                .disabled(testing)
            }
        }
    }
}
