import Foundation
import OpenCloudreveCore

/// 网页预览的打开方式（`WebViewerTarget` 的 Swift 版）
enum WebViewerTargetModel: Equatable {
    /// 应用内网页视图（交接后的站点页面或 custom 预览器）
    case embedded(URL)
    /// Safari 视图（custom 且 `openInNew`）
    case browser(URL)
    /// 非 http(s) 地址交给系统
    case app(URL)
    /// 还在准备地址
    case none

    init(target: WebViewerTarget?) {
        guard let target, let url = URL(string: target.url) else {
            self = .none
            return
        }
        switch onEnum(of: target) {
        case .embedded: self = .embedded(url)
        case .externalBrowser: self = .browser(url)
        case .externalApp: self = .app(url)
        }
    }
}
