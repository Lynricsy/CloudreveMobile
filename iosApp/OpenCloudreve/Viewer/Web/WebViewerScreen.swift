import SwiftUI
import OpenCloudreveCore

/// 网页预览器（epub、drawio、excalidraw、photopea、wopi、custom）：经会话交接免登录打开站点内预览，
/// custom 的 `openInNew` 用 Safari 视图，非 http(s) 地址交给系统（如 `nplayer://`）
struct WebViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ViewModelScope({
            CoreGraph.shared.viewerWebViewModel(params: request.params, darkMode: colorScheme == .dark)
        }) { host in
            WebViewerContent(host: host, chrome: chrome)
        }
    }
}
