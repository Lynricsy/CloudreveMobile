import SwiftUI
import OpenCloudreveCore

/// 列表模式的一行：缩略图/图标、名称、大小与时间（回收站为原位置与清除倒计时）、软链接/已分享角标
struct FileRowLabel: View {
    let file: FileItem
    let thumbnails: FileThumbnailSource
    let isTrash: Bool

    var body: some View {
        HStack(spacing: 12) {
            FileThumbnail(file: file, source: thumbnails, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: file.name)
                    .lineLimit(2)
                    .truncationMode(.middle)
                Text(verbatim: subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            FileBadges(file: file)
        }
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }

    private var subtitle: String {
        if isTrash {
            let text = FileFormat.trashSubtitle(file)
            if !text.isEmpty { return text }
        }
        let date = FileFormat.dateTime(file.updatedAt) ?? ""
        // 目录与软链接没有可信的大小
        if file.isFolder || file.isSymbolic { return date }
        let size = FileFormat.bytes(file.size)
        return date.isEmpty ? size : size + " · " + date
    }
}
