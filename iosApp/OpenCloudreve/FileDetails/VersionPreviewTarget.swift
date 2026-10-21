import OpenCloudreveCore

/// “预览此版本”的全屏目标
struct VersionPreviewTarget: Identifiable {
    let file: FileItem
    /// 版本实体 id
    let entityId: String

    var id: String { entityId }
}
