import OpenCloudreveCore

/// WebDAV 账号编辑弹层的目标：`item` 为 nil 表示新建
struct DavEditorTarget: Identifiable {
    let item: DavAccountItem?
    var id: String { item?.id ?? "new" }
}
