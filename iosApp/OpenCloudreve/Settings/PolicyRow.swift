import SwiftUI
import OpenCloudreveCore

/// 一个存储策略：名称、类型、单文件大小上限
struct PolicyRow: View {
    let item: PolicyItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: item.policy.name)
                    .font(.headline)
                if item.isDefault {
                    Text("默认")
                        .font(.caption.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.tint.opacity(0.15), in: .capsule)
                        .foregroundStyle(.tint)
                }
            }
            Text(verbatim: PolicyTypeName.name(item.policy.type))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if item.policy.maxSize > 0 {
                Text("单个文件最大 \(SettingsFormat.bytes(item.policy.maxSize))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if item.policy.encryption {
                Label("加密存储", systemImage: "lock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
