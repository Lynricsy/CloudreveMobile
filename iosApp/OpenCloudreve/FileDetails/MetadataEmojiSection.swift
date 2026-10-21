import SwiftUI
import OpenCloudreveCore

/// 图标：站点 emoji 预设按分类显示（分类键本身是 emoji），点按立即设置；可清除
struct MetadataEmojiSection: View {
    let host: MetadataEditHost

    @State private var category: String?

    var body: some View {
        let state = host.state
        let presets = state.emojiPresets
        // Kotlin Map 转成 Swift 字典后顺序丢失，按键排序保持稳定
        let categories = presets.keys.sorted()
        Section("图标") {
            if categories.isEmpty {
                Text("站点未提供 emoji 图标")
                    .foregroundStyle(.secondary)
            } else {
                let current = selectedCategory(categories: categories, presets: presets, emoji: state.emoji)
                if categories.count > 1 {
                    Picker("分类", selection: categoryBinding(current)) {
                        ForEach(categories, id: \.self) { key in
                            Text(verbatim: key).tag(key)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 40), spacing: 6)], spacing: 6) {
                    ForEach(presets[current] ?? [], id: \.self) { emoji in
                        Button {
                            host.send(MetadataEditIntentSetEmoji(emoji: emoji))
                        } label: {
                            Text(verbatim: emoji)
                                .font(.title2)
                                .frame(width: 40, height: 40)
                                .background(
                                    state.emoji == emoji ? AnyShapeStyle(.tint.opacity(0.2)) : AnyShapeStyle(Color.clear),
                                    in: .rect(cornerRadius: 8)
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("metadata.emoji.\(emoji)")
                        .accessibilityAddTraits(state.emoji == emoji ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
                .disabled(!state.canEdit || state.saving)
            }
            if state.emoji != nil {
                Button("清除图标", role: .destructive) {
                    host.send(MetadataEditIntentSetEmoji(emoji: nil))
                }
                .disabled(!state.canEdit || state.saving)
            }
        }
    }

    /// 当前分类：用户选择 > 当前 emoji 所在分类 > 第一个分类
    private func selectedCategory(categories: [String], presets: [String: [String]], emoji: String?) -> String {
        if let category, presets[category] != nil { return category }
        if let emoji, let owner = categories.first(where: { presets[$0]?.contains(emoji) == true }) { return owner }
        return categories[0]
    }

    private func categoryBinding(_ current: String) -> Binding<String> {
        Binding(get: { current }, set: { category = $0 })
    }
}
