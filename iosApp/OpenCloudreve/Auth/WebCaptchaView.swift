import SwiftUI
import WebKit

/// 加载站点 `/captcha?theme=&lng=` 页面；页面通过 `window.webkit.messageHandlers.captcha.postMessage(json)`
/// 回传 `{captcha?, ticket?}` 的 JSON 字符串
struct WebCaptchaView: UIViewRepresentable {
    static let handlerName = "captcha"

    let url: URL
    let onToken: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onToken: onToken)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(context.coordinator, name: Self.handlerName)
        let webView = WKWebView(frame: .zero, configuration: configuration)
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
    final class Coordinator: NSObject, WKScriptMessageHandler {
        var onToken: (String) -> Void

        init(onToken: @escaping (String) -> Void) {
            self.onToken = onToken
        }

        // WKScriptMessageHandler 在 SDK 中是主线程隔离协议，WebKit 在主线程投递脚本消息
        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard let json = message.body as? String else { return }
            onToken(json)
        }
    }
}
