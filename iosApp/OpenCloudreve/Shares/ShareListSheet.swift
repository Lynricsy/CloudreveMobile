/// 分享列表弹出的 sheet
enum ShareListSheet: Identifiable {
    /// 编辑分享
    case edit(shareId: String)
    /// 二维码
    case qr(link: String, name: String)

    var id: String {
        switch self {
        case let .edit(shareId): "edit:" + shareId
        case let .qr(link, _): "qr:" + link
        }
    }
}
