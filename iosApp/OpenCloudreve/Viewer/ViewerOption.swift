import Foundation
import OpenCloudreveCore

/// 预览器候选：从 Kotlin `Viewer` 摘出的可发送字段（解析在非隔离一侧完成，只把它带回主线程）
struct ViewerOption: Identifiable, Hashable, Sendable {
    let id: String
    let type: String
    let displayName: String
    /// 移动端原生实现的内置预览器（其余内置与 wopi/custom 走网页）
    let isNative: Bool

    init(viewer: Viewer) {
        id = viewer.id
        type = viewer.type
        displayName = viewer.displayName
        isNative = viewer.type == Viewer.companion.TYPE_BUILTIN && BuiltinViewerId.shared.NATIVE.contains(viewer.id)
    }

    /// 显示名：原生预览器用本地名称；服务端默认表多为网页端 i18n key，已知的翻译，未知的原样或用 id
    var label: String {
        if isNative {
            switch id {
            case "image": return String(localized: "图片查看器")
            case "video": return String(localized: "视频播放器")
            case "music": return String(localized: "音乐播放器")
            case "pdf": return String(localized: "PDF 阅读器")
            case "monaco": return String(localized: "文本编辑器")
            case "markdown": return String(localized: "Markdown 编辑器")
            case "archive": return String(localized: "压缩包预览")
            default: break
            }
        }
        switch displayName {
        case "fileManager.epubViewer": return String(localized: "EPUB 阅读器")
        case "fileManager.googledocs": return String(localized: "Google Docs 预览")
        case "fileManager.m365viewer": return String(localized: "Microsoft 365 在线预览")
        default:
            return displayName.isEmpty || displayName.hasPrefix("fileManager.") ? id : displayName
        }
    }
}
