/// 分享行上的操作（上下文菜单与滑动）
enum ShareRowAction {
    /// 复制链接
    case copy
    /// 显示二维码
    case qr
    /// 编辑分享
    case edit
    /// 前往源文件所在目录
    case source
    /// 删除（需确认）
    case delete
}
