import SwiftUI

/// 存储容量进度条：已用 / 总量
struct QuotaView: View {
    let used: Int64
    let total: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("存储空间", systemImage: "internaldrive")
                .font(.subheadline.weight(.semibold))
            ProgressView(value: Double(min(used, max(total, 1))), total: Double(max(total, 1)))
                .tint(fraction > 0.9 ? Color.red : Color.accentColor)
            Text("已用 \(FileFormat.bytes(used))，共 \(FileFormat.bytes(total))", comment: "存储容量，参数为已用与总量")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("files.quota")
    }

    private var fraction: Double {
        total > 0 ? Double(used) / Double(total) : 0
    }
}
