import SwiftUI
import OpenCloudreveCore

/// 负载均衡子策略：名称、类型与权重占比
struct PolicyWeightRow: View {
    let weight: PolicyWeight

    var body: some View {
        LabeledContent {
            Text(weight.share, format: .percent.precision(.fractionLength(0)))
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: weight.policy.name)
                Text(verbatim: PolicyTypeName.name(weight.policy.type))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.leading, 12)
    }
}
