import SwiftUI

/// 元数据一行：键在左，值在右（可多行、可选择复制）
struct MetadataRowView: View {
    let row: MetadataRow

    var body: some View {
        LabeledContent {
            Text(verbatim: row.value)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        } label: {
            Text(verbatim: row.key)
        }
    }
}
