import SwiftUI

/// 标题 + 值的一行，点按拷贝值
struct CopyableRow: View {
    let title: LocalizedStringKey
    let value: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = value
            copied = true
        } label: {
            LabeledContent {
                HStack(spacing: 6) {
                    Text(verbatim: value)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                }
            } label: {
                Text(title)
            }
            .contentShape(.rect)
        }
        // 默认按钮样式会把整行文字染成强调色；plain 保持标题/数值的主次颜色
        .buttonStyle(.plain)
        .accessibilityHint(Text("拷贝"))
        .sensoryFeedback(.success, trigger: copied)
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(2))
            copied = false
        }
    }
}
