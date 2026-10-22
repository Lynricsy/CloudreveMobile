import Foundation

/// 验证码网页的信任边界：只有服务器站点的验证码页面（同源、同一路径的主 frame）可以导航到并向原生回传结果；
/// 验证码供应商的子 frame / 子资源照常加载，但它们发出的原生消息不被采纳
struct WebCaptchaPolicy: Equatable, Sendable {
    let scheme: String
    let host: String
    let port: Int
    /// 验证码页面路径（站点可能部署在子路径下，如 `/cloud/captcha`）
    let path: String

    /// [pageURL]：加载的验证码页面（站点地址 + `/captcha?...`）
    init?(pageURL: URL) {
        guard let scheme = pageURL.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = pageURL.host()?.lowercased(), !host.isEmpty else { return nil }
        self.scheme = scheme
        self.host = host
        port = Self.normalizedPort(pageURL.port, scheme: scheme)
        path = pageURL.path(percentEncoded: false)
    }

    /// 主 frame 只能停留在验证码页面（查询参数可变，例如切换主题/语言）
    func allowsMainFrameNavigation(to url: URL) -> Bool {
        isSameOrigin(scheme: url.scheme, host: url.host(), port: url.port) && url.path(percentEncoded: false) == path
    }

    /// 脚本消息只接受来自站点源的主 frame（`WKFrameInfo.securityOrigin`，默认端口为 0）
    func acceptsMessage(isMainFrame: Bool, scheme: String, host: String, port: Int) -> Bool {
        isMainFrame && isSameOrigin(scheme: scheme, host: host, port: port == 0 ? nil : port)
    }

    private func isSameOrigin(scheme: String?, host: String?, port: Int?) -> Bool {
        guard let scheme = scheme?.lowercased(), let host = host?.lowercased() else { return false }
        return scheme == self.scheme && host == self.host && Self.normalizedPort(port, scheme: scheme) == self.port
    }

    private static func normalizedPort(_ port: Int?, scheme: String) -> Int {
        if let port { return port }
        return scheme == "https" ? 443 : 80
    }
}
