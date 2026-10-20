import SwiftUI

/// 文本显示选项：等宽字体、自动换行；有未保存更改时可还原
struct TextFormatMenu: View {
    @Binding var monospaced: Bool
    @Binding var wraps: Bool
    let canDiscard: Bool
    let onDiscard: () -> Void

    var body: some View {
        Menu {
            Toggle("等宽字体", systemImage: "textformat", isOn: $monospaced)
            Toggle("自动换行", systemImage: "text.word.spacing", isOn: $wraps)
            if canDiscard {
                Divider()
                Button("还原更改", systemImage: "arrow.uturn.backward", role: .destructive, action: onDiscard)
            }
        } label: {
            Label("显示选项", systemImage: "textformat.size")
        }
        .accessibilityIdentifier("text.format")
    }
}
