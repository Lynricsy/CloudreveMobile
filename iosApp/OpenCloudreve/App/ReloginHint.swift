/// 修改密码后账户已在本地登出：根视图据此直接进入该站点的登录页（预填邮箱），而不是服务器地址页
struct ReloginHint: Equatable {
    let siteUrl: String
    let email: String
}
