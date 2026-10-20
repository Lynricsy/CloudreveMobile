import Foundation

/// 一条文件类型图标设置（站点 `explorer.icons` 或服务端默认表 `inventory/setting.go:defaultIcons`）
struct FileIconSetting: Sendable, Equatable {
    var exts: [String]
    var icon: String?
    var color: String?
    var colorDark: String?
    var img: String?
}

extension FileIconSetting {
    init(exts: [String], icon: String?, color: String?, colorDark: String? = nil) {
        self.init(exts: exts, icon: icon, color: color, colorDark: colorDark, img: nil)
    }
}
