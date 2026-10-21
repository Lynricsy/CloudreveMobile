import SwiftUI
import OpenCloudreveCore

/// 自定义属性只读展示（信息页）：评分为星级，布尔为是/否，链接可点，多选逗号分隔
struct CustomPropValueView: View {
    /// 属性名（定义缺失时为属性 id）
    let name: String
    let definition: CustomPropDefinition?
    let value: String

    var body: some View {
        LabeledContent {
            valueView
        } label: {
            Text(verbatim: CustomPropText.name(name))
        }
    }

    @ViewBuilder
    private var valueView: some View {
        switch definition?.type ?? "" {
        case CustomPropDefinition.companion.TYPE_RATING:
            let max = Int(definition?.max ?? 0) > 0 ? Int(definition?.max ?? 0) : 5
            let rating = Int(value) ?? 0
            HStack(spacing: 2) {
                ForEach(1...max, id: \.self) { star in
                    Image(systemName: star <= rating ? "star.fill" : "star")
                        .foregroundStyle(star <= rating ? Color.yellow : Color.secondary)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("\(rating) 星", comment: "评分的无障碍描述"))
        case CustomPropDefinition.companion.TYPE_BOOLEAN:
            if CustomPropCodec.shared.isTrue(value: value) {
                Text("是")
            } else {
                Text("否")
            }
        case CustomPropDefinition.companion.TYPE_LINK:
            if let url = URL(string: value), url.scheme != nil {
                Link(destination: url) {
                    Text(verbatim: value)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            } else {
                Text(verbatim: value)
            }
        case CustomPropDefinition.companion.TYPE_MULTI_SELECT:
            Text(verbatim: CustomPropCodec.shared.decodeMultiSelect(value: value).joined(separator: ", "))
                .multilineTextAlignment(.trailing)
        default:
            Text(verbatim: value)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
    }
}
