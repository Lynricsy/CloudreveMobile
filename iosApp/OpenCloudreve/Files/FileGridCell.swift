import SwiftUI
import OpenCloudreveCore

/// 网格模式的单元格：方形缩略图/图标 + 名称；多选时右上角显示选中标记
struct FileGridCell: View {
    let file: FileItem
    let thumbnails: FileThumbnailSource
    let selecting: Bool
    let selected: Bool

    @ScaledMetric(relativeTo: .body) private var tileSize: CGFloat = 96

    var body: some View {
        VStack(spacing: 6) {
            FileThumbnail(file: file, source: thumbnails, size: tileSize, cornerRadius: 12)
                .overlay(alignment: .topTrailing) {
                    if selecting {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(selected ? Color.white : Color.secondary, selected ? Color.accentColor : Color.clear)
                            .background(.background, in: .circle)
                            .padding(4)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    FileBadges(file: file)
                        .padding(4)
                }
            Text(verbatim: file.name)
                .font(.footnote)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .top)
        }
        .padding(6)
        .background(selected ? Color.accentColor.opacity(0.15) : Color.clear, in: .rect(cornerRadius: 14))
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
