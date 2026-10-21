import SwiftUI
import OpenCloudreveCore

/// 文件夹图标颜色（仅全部为文件夹时），点按立即生效；“无颜色”恢复默认
struct MetadataIconColorSection: View {
    let host: MetadataEditHost

    var body: some View {
        let state = host.state
        Section("图标颜色") {
            ColorPalette(
                colors: state.presetColors,
                selected: state.iconColor,
                identifierPrefix: "metadata.iconColor",
                disabled: state.saving
            ) { color in
                host.send(MetadataEditIntentSetIconColor(color: color))
            }
        }
    }
}
