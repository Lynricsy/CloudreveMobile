import SwiftUI

/// 文件名编码（非 UTF-8 的 zip 中文名乱码时切换）
struct ArchiveEncodingMenu: View {
    let current: String?
    let options: [String]
    /// nil = 自动
    let onSelect: (String?) -> Void

    var body: some View {
        Menu {
            Button {
                onSelect(nil)
            } label: {
                if current == nil {
                    Label("自动", systemImage: "checkmark")
                } else {
                    Text("自动")
                }
            }
            ForEach(options, id: \.self) { option in
                Button {
                    onSelect(option)
                } label: {
                    if option == current {
                        Label(option, systemImage: "checkmark")
                    } else {
                        Text(verbatim: option)
                    }
                }
            }
        } label: {
            Label("文件名编码", systemImage: "character.book.closed")
        }
        .accessibilityIdentifier("archive.encoding")
    }
}
