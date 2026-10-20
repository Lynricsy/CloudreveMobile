import SwiftUI
import OpenCloudreveCore

/// 文件操作弹出的 sheet（同一时刻最多一个）
enum FileSheet: Identifiable {
    /// 移动/复制目标选择
    case folderPicker(FolderPickRequest)
    /// 目录挂载存储策略
    case mountPolicy(id: UUID, folder: FileItem)
    /// 文件详细信息
    case info(id: UUID, file: FileItem)
    /// 直链生成结果
    case sourceLinks(id: UUID, links: [SourceLink], failures: [OperationFailure])
    /// 打开分享链接（软链接的“前往分享链接”）
    case shareVisit(id: UUID, link: String)
    /// 后续阶段接入的界面
    case hook(id: UUID, content: AnyView)

    var id: UUID {
        switch self {
        case let .folderPicker(request): request.id
        case let .mountPolicy(id, _), let .info(id, _), let .sourceLinks(id, _, _), let .shareVisit(id, _), let .hook(id, _): id
        }
    }
}
