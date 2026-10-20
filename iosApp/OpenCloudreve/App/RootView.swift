import SwiftUI
import OpenCloudreveCore

/// 根路由：无当前账户 → 服务器地址页；当前账户会话失效 → 该站点登录页（预填邮箱）；否则主界面。
/// 用视图标识区分各分支，账户变化时旧分支（及其中的 Kotlin VM）整体销毁。
struct RootView: View {
    let session: AppSession

    var body: some View {
        if let account = session.activeAccount {
            if account.sessionExpired {
                AuthFlowView(root: .login(siteUrl: account.siteUrl, email: account.email))
                    .id("relogin:" + account.id)
            } else {
                MainTabView()
                    .id("main:" + account.id)
            }
        } else {
            AuthFlowView(root: .server)
                .id("signed-out")
        }
    }
}
