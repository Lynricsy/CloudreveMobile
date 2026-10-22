import SwiftUI
import WebKit

/// 加载站点 `/captcha?theme=&lng=` 页面；页面通过 `window.webkit.messageHandlers.captcha.postMessage(json)`
/// 回传 `{captcha?, ticket?}` 的 JSON 字符串。只采纳站点源主 frame 的消息，主 frame 只能停留在验证码页面
/// （见 `WebCaptchaPolicy`）；验证码供应商的子 frame 照常加载。
struct WebCaptchaView: UIViewRepresentable {
    static let handlerName = "captcha"

    let url: URL
    let onToken: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(policy: WebCaptchaPolicy(pageURL: url), onToken: onToken)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(context.coordinator, name: Self.handlerName)
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onToken = onToken
    }

    /// `WKUserContentController` 强引用消息处理者，拆除时移除以免循环引用
    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: handlerName)
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        /// nil：页面地址不是 http(s) 站点，任何消息与导航都不信任
        let policy: WebCaptchaPolicy?
        var onToken: (String) -> Void

        init(policy: WebCaptchaPolicy?, onToken: @escaping (String) -> Void) {
            self.policy = policy
            self.onToken = onToken
        }

        // WKScriptMessageHandler 在 SDK 中是主线程隔离协议，WebKit 在主线程投递脚本消息
        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            let origin = message.frameInfo.securityOrigin
            guard let policy,
                  policy.acceptsMessage(
                      isMainFrame: message.frameInfo.isMainFrame,
                      scheme: origin.protocol,
                      host: origin.host,
                      port: origin.port
                  ),
                  let json = message.body as? String else { return }
            onToken(json)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            // 子 frame（验证码供应商的 iframe 及其资源）照常加载；没有目标 frame 的新窗口请求一律拒绝
            guard let frame = navigationAction.targetFrame else { return .cancel }
            guard frame.isMainFrame else { return .allow }
            guard let policy, let url = navigationAction.request.url, policy.allowsMainFrameNavigation(to: url) else {
                return .cancel
            }
            return .allow
        }
    }
}
