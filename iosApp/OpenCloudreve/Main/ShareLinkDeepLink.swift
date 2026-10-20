import SwiftUI
import OpenCloudreveCore

/// 处理分享链接深链：`opencloudreve://open?url=<分享链接>`（分享扩展收到分享链接时转交）与
/// `cloudreve://<id>[:密码]@share`，以 sheet 打开分享浏览
struct ShareLinkDeepLink: ViewModifier {
    let accountId: String?

    @State private var link: OpenedLink?

    private struct OpenedLink: Identifiable {
        let id = UUID()
        let text: String
    }

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in
                guard accountId != nil, Self.isShareLink(url) else { return }
                link = OpenedLink(text: url.absoluteString)
            }
            .sheet(item: $link) { opened in
                if let accountId {
                    ShareVisitSheet(accountId: accountId, link: opened.text)
                }
            }
    }

    static func isShareLink(_ url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "opencloudreve": url.host() == "open"
        case "cloudreve": url.absoluteString.contains("@share")
        default: false
        }
    }
}
