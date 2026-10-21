import SwiftUI

/// 选择某个标签的颜色（草稿，需“保存标签”提交）
struct TagColorSheet: View {
    let target: TagColorTarget
    let colors: [String]
    let pick: (String?) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ColorPalette(colors: colors, selected: target.color) { color in
                        pick(color)
                        dismiss()
                    }
                } header: {
                    Text("标签颜色")
                }
            }
            .navigationTitle(Text(verbatim: target.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭", role: .close) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
