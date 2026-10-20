import SafariServices
import SwiftUI

/// 用 `SFSafariViewController` 打开网页（会话交接地址：网页端兑换一次性 code 后免登录进入目标页）
struct HandoffSafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}
