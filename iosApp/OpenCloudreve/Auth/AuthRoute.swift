/// 登录流程内的导航目标
enum AuthRoute: Hashable {
    /// 服务器地址页
    case server
    /// 某站点的登录页（`email` 用于会话失效后预填）
    case login(siteUrl: String, email: String?)
}
