import SwiftUI
import OpenCloudreveCore

/// 存储空间用量
struct MeCapacitySection: View {
    let capacity: Capacity?
    let usedFraction: Double
    let overQuota: Bool

    var body: some View {
        if let capacity {
            Section("存储空间") {
                VStack(alignment: .leading, spacing: 8) {
                    ProgressView(value: min(max(usedFraction, 0), 1))
                        .tint(overQuota ? .red : nil)
                    Text("已使用 \(SettingsFormat.bytes(capacity.used))，共 \(SettingsFormat.bytes(capacity.total))")
                        .font(.footnote)
                        .foregroundStyle(overQuota ? .red : .secondary)
                }
                .padding(.vertical, 4)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("me.capacity")
            }
        }
    }
}
