import SwiftUI
import OpenCloudreveCore

/// 打开分享链接深链：`opencloudreve://open?url=<分享链接>`（分享扩展收到分享链接时转交）与
/// `cloudreve://<id>[:密码]@share[/路径]`。`RootView` 收到后记入 `AppSession.pendingShareLink`（未登录时先保留），
/// 主界面出现或链接到达时取走并以 sheet 打开分享浏览。
struct ShareLinkDeepLink: ViewModifier {
    let accountId: String?

    @Environment(AppSession.self) private var session
    @State private var link: OpenedLink?

    private struct OpenedLink: Identifiable {
        let id = UUID()
        let text: String
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: session.pendingShareLink, initial: true) { _, pending in
                guard accountId != nil, let pending else { return }
                session.pendingShareLink = nil
                link = OpenedLink(text: pending)
            }
            .sheet(item: $link) { opened in
                if let accountId {
                    ShareVisitSheet(accountId: accountId, link: opened.text)
                }
            }
    }

    /// 是否为本模块处理的分享链接（`opencloudreve://oauth/callback` 等其它深链返回 false）
    static func isShareLink(_ url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "opencloudreve": url.host()?.lowercased() == "open"
        case "cloudreve": url.host()?.lowercased() == "share" && url.user() != nil
        default: false
        }
    }
}
