import SwiftUI
import OpenCloudreveCore

/// 站点主题色色板：一排色块，选中项打勾
struct ThemeColorPicker: View {
    let options: [ThemeColorOption]
    let selected: String?
    let disabled: Bool
    let select: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 12) {
                ForEach(options, id: \.key) { option in
                    let isSelected = option.key == selected
                    Button {
                        select(option.key)
                    } label: {
                        Circle()
                            .fill(ThemeColor.parse(colorScheme == .dark ? option.darkPrimary : option.lightPrimary) ?? .accentColor)
                            .frame(width: 40, height: 40)
                            .overlay {
                                if isSelected {
                                    Image(systemName: "checkmark")
                                        .font(.headline)
                                        .foregroundStyle(.white)
                                }
                            }
                    }
                    .buttonStyle(.plain)
                    .disabled(disabled)
                    .accessibilityLabel(Text(verbatim: option.key))
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
    }
}
