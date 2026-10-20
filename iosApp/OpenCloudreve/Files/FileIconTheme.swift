import SwiftUI
import OpenCloudreveCore

/// 文件类型图标表：扩展名 → 设置。元数据 `customize:emoji` 覆盖图标、`customize:icon_color` 覆盖颜色（规格 §7.4）。
struct FileIconTheme: Sendable, Equatable {
    private let byExt: [String: FileIconSetting]
    let siteUrl: String

    init(settings: [FileIconSetting], siteUrl: String) {
        var map: [String: FileIconSetting] = [:]
        for setting in settings {
            for ext in setting.exts where map[ext.lowercased()] == nil {
                map[ext.lowercased()] = setting
            }
        }
        byExt = map
        self.siteUrl = siteUrl
    }

    /// 站点 explorer 配置中的图标表；站点未配置时用默认表
    init(explorer: SiteExplorer?, siteUrl: String) {
        let parsed = explorer?.parsedIcons() ?? []
        if parsed.isEmpty {
            self.init(settings: Self.defaultSettings, siteUrl: siteUrl)
        } else {
            self.init(
                settings: parsed.map {
                    FileIconSetting(exts: $0.exts, icon: $0.icon, color: $0.color, colorDark: $0.colorDark, img: $0.img)
                },
                siteUrl: siteUrl
            )
        }
    }

    /// 文件（非目录）对应的设置；未知扩展名为 nil
    func setting(for file: FileItem) -> FileIconSetting? {
        file.isFolder ? nil : byExt[FileFormat.ext(file)]
    }

    /// 自定义图片地址（仅位图；SVG 无法用系统图片解码，回退到 SF Symbol）
    func imageURL(_ setting: FileIconSetting) -> URL? {
        guard let img = setting.img?.trimmingCharacters(in: .whitespaces), !img.isEmpty,
              !img.lowercased().hasSuffix(".svg") else { return nil }
        if img.hasPrefix("http://") || img.hasPrefix("https://") { return URL(string: img) }
        let base = siteUrl.hasSuffix("/") ? String(siteUrl.dropLast()) : siteUrl
        return URL(string: base + "/" + img.drop { $0 == "/" })
    }

    static let fallback = FileIconTheme(settings: defaultSettings, siteUrl: "")

    /// 前端 `builtInIcons` 名称 → SF Symbol
    static func symbol(for builtIn: String?) -> String? {
        switch builtIn {
        case "audio": "music.note"
        case "video": "film"
        case "image": "photo"
        case "raw": "camera.aperture"
        case "pdf": "doc.richtext"
        case "word", "markdown": "doc.text"
        case "ppt": "play.rectangle"
        case "excel": "tablecells"
        case "text": "doc.plaintext"
        case "torrent": "arrow.down.circle"
        case "zip": "doc.zipper"
        case "exe": "macwindow"
        case "android": "shippingbox"
        case "js", "go", "c", "cpp", "python", "rust": "chevron.left.forwardslash.chevron.right"
        case "book": "book.closed"
        case "flowchart": "point.3.connected.trianglepath.dotted"
        case "whiteboard": "scribble"
        default: nil
        }
    }

    /// 解析 `#rgb` / `#rrggbb` / `#aarrggbb`；无效返回 nil
    static func color(hex value: String?) -> Color? {
        guard var hex = value?.trimmingCharacters(in: .whitespaces), !hex.isEmpty else { return nil }
        if hex.hasPrefix("#") { hex.removeFirst() }
        let full: String
        switch hex.count {
        case 3: full = "FF" + hex.map { "\($0)\($0)" }.joined()
        case 6: full = "FF" + hex
        case 8: full = hex
        default: return nil
        }
        guard let argb = UInt32(full, radix: 16) else { return nil }
        return Color(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }

    /// 服务端默认图标表（站点未返回 `icons` 时使用）
    static let defaultSettings: [FileIconSetting] = [
        .init(exts: ["mp3", "flac", "ape", "wav", "acc", "ogg", "m4a"], icon: "audio", color: "#651fff"),
        .init(exts: ["m3u8", "mp4", "flv", "avi", "wmv", "mkv", "rm", "rmvb", "mov", "ogv"], icon: "video", color: "#d50000"),
        .init(
            exts: ["bmp", "iff", "png", "gif", "jpg", "jpeg", "psd", "svg", "webp", "heif", "heic", "tiff", "avif"],
            icon: "image",
            color: "#d32f2f"
        ),
        .init(
            exts: ["3fr", "ari", "arw", "bay", "braw", "crw", "cr2", "cr3", "cap", "dcs", "dcr", "dng", "drf", "eip", "erf", "fff",
                   "gpr", "iiq", "k25", "kdc", "mdc", "mef", "mos", "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d",
                   "raf", "raw", "rwl", "rw2", "rwz", "sr2", "srf", "srw", "tif", "x3f"],
            icon: "raw",
            color: "#d32f2f"
        ),
        .init(exts: ["pdf"], icon: "pdf", color: "#f44336"),
        .init(exts: ["doc", "docx"], icon: "word", color: "#538ce5"),
        .init(exts: ["ppt", "pptx"], icon: "ppt", color: "#EF633F"),
        .init(exts: ["xls", "xlsx", "csv"], icon: "excel", color: "#4caf50"),
        .init(exts: ["txt", "html"], icon: "text", color: "#607d8b"),
        .init(exts: ["torrent"], icon: "torrent", color: "#5c6bc0"),
        .init(exts: ["zip", "gz", "xz", "tar", "rar", "7z", "bz2", "z"], icon: "zip", color: "#f9a825"),
        .init(exts: ["exe", "msi"], icon: "exe", color: "#1a237e"),
        .init(exts: ["apk"], icon: "android", color: "#8bc34a"),
        .init(exts: ["go"], icon: "go", color: "#16b3da"),
        .init(exts: ["py"], icon: "python", color: "#3776ab"),
        .init(exts: ["c"], icon: "c", color: "#a4c639"),
        .init(exts: ["cpp"], icon: "cpp", color: "#f34b7d"),
        .init(exts: ["js", "jsx"], icon: "js", color: "#f4d003"),
        .init(exts: ["epub"], icon: "book", color: "#81b315"),
        .init(exts: ["rs"], icon: "rust", color: "#000", colorDark: "#fff"),
        .init(exts: ["drawio"], icon: "flowchart", color: "#F08705"),
        .init(exts: ["dwb"], icon: "whiteboard", color: "#F08705"),
        .init(exts: ["md"], icon: "markdown", color: "#383838", colorDark: "#cbcbcb"),
    ]
}

extension EnvironmentValues {
    /// 当前账户站点的文件图标表（文件 Tab / 搜索 Tab 根部注入）
    @Entry var fileIconTheme: FileIconTheme = .fallback
}
