import Foundation

/// 移动/复制时的目标目录选择请求
struct FolderPickRequest: Identifiable {
    enum Purpose {
        case move
        case copy
    }

    let id = UUID()
    let purpose: Purpose
    /// 被移动/复制的文件 URI
    let sources: [String]
    /// 选择器起始目录（通常为来源所在目录）
    let initialUri: String
    /// 不能作为目标的目录（移动时为被移动的目录本身）
    let excluded: [String]
}
