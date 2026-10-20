import SwiftUI

/// 文件菜单中的动作（含由后续阶段实现的入口，是否显示还取决于 `FileFeatures` 是否接入）
enum FileAction: CaseIterable, Hashable {
    case open
    case download
    case share
    case rename
    case move
    case copy
    case delete
    case restore
    case pin
    case info
    case versions
    case metadata
    case directLink
    case resetThumbnail
    case disableThumbnail
    case goToSharedLink
    case goToParent
    case mountPolicy
    case relocate
    case createArchive
    case extractArchive
    case remoteDownload

    /// 破坏性动作（菜单中显示为红色）
    var isDestructive: Bool { self == .delete }

    var systemImage: String {
        switch self {
        case .open: "eye"
        case .download: "arrow.down.circle"
        case .share: "square.and.arrow.up"
        case .rename: "pencil"
        case .move: "folder"
        case .copy: "doc.on.doc"
        case .delete: "trash"
        case .restore: "arrow.uturn.backward"
        case .pin: "pin"
        case .info: "info.circle"
        case .versions: "clock.arrow.circlepath"
        case .metadata: "tag"
        case .directLink: "link"
        case .resetThumbnail: "photo"
        case .disableThumbnail: "photo.badge.exclamationmark"
        case .goToSharedLink: "arrowshape.turn.up.right"
        case .goToParent: "folder.badge.questionmark"
        case .mountPolicy: "externaldrive"
        case .relocate: "externaldrive.badge.plus"
        case .createArchive: "archivebox"
        case .extractArchive: "archivebox.fill"
        case .remoteDownload: "arrow.down.to.line.circle"
        }
    }

    /// 菜单文字；`pinned` 为 true 时固定动作显示“取消固定”
    func title(pinned: Bool = false) -> LocalizedStringKey {
        switch self {
        case .open: "打开"
        case .download: "下载"
        case .share: "分享"
        case .rename: "重命名"
        case .move: "移动"
        case .copy: "复制"
        case .delete: "删除"
        case .restore: "还原"
        case .pin: pinned ? "取消固定" : "固定"
        case .info: "详细信息"
        case .versions: "版本"
        case .metadata: "标签与元数据"
        case .directLink: "获取直链"
        case .resetThumbnail: "重新生成缩略图"
        case .disableThumbnail: "不生成缩略图"
        case .goToSharedLink: "前往分享链接"
        case .goToParent: "前往所在目录"
        case .mountPolicy: "挂载存储策略"
        case .relocate: "转移存储策略"
        case .createArchive: "压缩"
        case .extractArchive: "解压"
        case .remoteDownload: "创建离线下载"
        }
    }
}
