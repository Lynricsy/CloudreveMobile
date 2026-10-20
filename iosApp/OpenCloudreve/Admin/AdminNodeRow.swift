import SwiftUI
import OpenCloudreveCore

/// 节点一行：名称、类型、状态、地址、权重
struct AdminNodeRow: View {
    let node: AdminNode
    let testing: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(verbatim: node.name)
                        .font(.headline)
                    AdminBadge(text: AdminLabels.nodeType(node.type), color: .blue)
                    AdminBadge(text: AdminLabels.nodeStatus(node.status), color: node.status == "active" ? .green : .orange)
                }
                Text(verbatim: node.server.isEmpty ? "—" : node.server)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text("权重 \(Int(node.weight))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if testing {
                ProgressView()
            }
        }
        .accessibilityElement(children: .combine)
    }
}
