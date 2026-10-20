import SwiftUI

/// 条目图标 + 名称 + 大小（文件）/ 进入箭头（目录）
struct ArchiveEntryLabel: View {
    let entry: ArchiveEntryItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.isDirectory ? "folder.fill" : "doc")
                .foregroundStyle(entry.isDirectory ? Color.accentColor : .secondary)
                .frame(width: 24)
                .accessibilityHidden(true)
            Text(verbatim: entry.name)
                .foregroundStyle(.primary)
                .lineLimit(2)
            Spacer(minLength: 8)
            if entry.isDirectory {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            } else {
                Text(verbatim: FileFormat.bytes(entry.size))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
