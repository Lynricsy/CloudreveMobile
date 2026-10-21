import SwiftUI
import OpenCloudreveCore

/// 添加标签：名称输入 + 颜色（加入草稿，需“保存标签”提交）
struct MetadataNewTagSection: View {
    let host: MetadataEditHost

    @State private var name = ""
    @State private var color: String?

    var body: some View {
        let state = host.state
        Section("添加标签") {
            TextField("标签名称", text: $name)
                .textInputAutocapitalization(.never)
                .submitLabel(.done)
                .onSubmit(add)
                .accessibilityIdentifier("metadata.tagName")
            ColorPalette(colors: state.presetColors, selected: color, identifierPrefix: "metadata.tagColor") { picked in
                color = picked
            }
            Button("添加", systemImage: "plus", action: add)
                .disabled(trimmedName.isEmpty || state.tags.contains { $0.name == trimmedName })
                .accessibilityIdentifier("metadata.addTag")
        }
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func add() {
        guard !trimmedName.isEmpty else { return }
        host.send(MetadataEditIntentAddTag(name: trimmedName, color: color))
        name = ""
        color = nil
    }
}
