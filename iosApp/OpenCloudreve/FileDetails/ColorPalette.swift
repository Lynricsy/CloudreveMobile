import SwiftUI

/// 预设颜色调色板（列表行内的圆点网格）；`allowNone` 时首项为“无颜色”。
/// 各色块的无障碍标识为 `<identifierPrefix>.<序号>`（序号按 `colors` 从 0 起），“无颜色”为 `<identifierPrefix>.none`。
struct ColorPalette: View {
    let colors: [String]
    /// 当前选中的颜色（nil 表示无颜色）
    let selected: String?
    var allowNone = true
    var identifierPrefix: String?
    var disabled = false
    let pick: (String?) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 34), spacing: 10)], spacing: 10) {
            if allowNone {
                Button {
                    pick(nil)
                } label: {
                    Label {
                        Text("无颜色")
                    } icon: {
                        swatch(color: nil, isSelected: selected == nil)
                    }
                    .labelStyle(.iconOnly)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(identifierPrefix.map { "\($0).none" } ?? "")
            }
            ForEach(Array(colors.enumerated()), id: \.offset) { index, hex in
                Button {
                    pick(hex)
                } label: {
                    Label {
                        Text(verbatim: hex)
                    } icon: {
                        swatch(color: FileIconTheme.color(hex: hex), isSelected: selected?.lowercased() == hex.lowercased())
                    }
                    .labelStyle(.iconOnly)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(identifierPrefix.map { "\($0).\(index)" } ?? "")
                .accessibilityAddTraits(selected?.lowercased() == hex.lowercased() ? .isSelected : [])
            }
        }
        .padding(.vertical, 4)
        .disabled(disabled)
    }

    /// 色块：选中时带对勾；无颜色为带斜线的空心圆
    private func swatch(color: Color?, isSelected: Bool) -> some View {
        ZStack {
            if let color {
                Circle().fill(color)
            } else {
                Circle().strokeBorder(.secondary, lineWidth: 1.5)
                Image(systemName: "line.diagonal")
                    .foregroundStyle(.secondary)
            }
            if isSelected {
                Image(systemName: "checkmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(color == nil ? Color.primary : Color.white)
            }
        }
        .frame(width: 30, height: 30)
    }
}
