import SwiftUI
import NukeUI
import OpenCloudreveCore

/// 文件/目录图标（不含缩略图）：emoji > 站点位图 > SF Symbol，颜色取 `customize:icon_color` 或图标表颜色
struct FileTypeIconView: View {
    let file: FileItem
    let size: CGFloat

    @Environment(\.fileIconTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Group {
            if let emoji = file.metadata[MetadataKeys.shared.EMOJI], !emoji.isEmpty {
                Text(verbatim: emoji)
                    .font(.system(size: size * 0.8))
            } else if file.isFolder {
                Image(systemName: "folder.fill")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(customColor ?? Color.accentColor)
            } else if let setting = theme.setting(for: file),
                      FileIconTheme.symbol(for: setting.icon) == nil,
                      let url = theme.imageURL(setting) {
                LazyImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit()
                    } else {
                        symbolImage("doc", setting: setting)
                    }
                }
            } else {
                let setting = theme.setting(for: file)
                symbolImage(FileIconTheme.symbol(for: setting?.icon) ?? "doc", setting: setting)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private var customColor: Color? {
        FileIconTheme.color(hex: file.metadata[MetadataKeys.shared.ICON_COLOR])
    }

    private func symbolImage(_ name: String, setting: FileIconSetting?) -> some View {
        let themed = colorScheme == .dark ? (setting?.colorDark ?? setting?.color) : setting?.color
        return Image(systemName: name)
            .resizable()
            .scaledToFit()
            .foregroundStyle(customColor ?? FileIconTheme.color(hex: themed) ?? Color.secondary)
    }
}
