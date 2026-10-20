import SwiftUI
import OpenCloudreveCore

struct WebViewerContent: View {
    let host: ViewerWebHost
    let chrome: ViewerChrome

    @Environment(\.openURL) private var openURL
    @State private var safariPage: HandoffPage?

    var body: some View {
        let state = host.state
        let target = WebViewerTargetModel(target: state.target)
        Group {
            if state.handoff == .unsupported {
                ContentUnavailableView {
                    Label("需要更新服务端", systemImage: "arrow.up.circle")
                } description: {
                    Text("此预览器需要 OpenCloudreve 服务端 4.19.11 或更高版本。")
                } actions: {
                    Button("用其他应用打开") { chrome.openExternally() }
                        .buttonStyle(.glass)
                }
            } else if let error = state.error {
                ViewerStatusView(status: .failed(ErrorText.message(error))) {
                    host.send(ViewerWebIntentReload.shared)
                }
            } else {
                switch target {
                case let .embedded(url):
                    EmbeddedWebView(url: url)
                        .ignoresSafeArea(edges: .bottom)
                        .accessibilityIdentifier("web.view")
                case let .browser(url):
                    ExternalTargetView(systemImage: "safari", title: "已在浏览器中打开") {
                        safariPage = HandoffPage(url: url)
                    }
                    .onAppear { safariPage = HandoffPage(url: url) }
                case let .app(url):
                    ExternalTargetView(systemImage: "arrow.up.forward.app", title: "已交给其他应用打开") {
                        openURL(url)
                    }
                    .onAppear { openURL(url) }
                case .none:
                    ViewerStatusView(status: .loading)
                }
            }
        }
        .viewerToolbar(chrome, title: state.file.name)
        .toolbar {
            if case .embedded = target {
                ToolbarItem(placement: .bottomBar) {
                    Button("重新载入", systemImage: "arrow.clockwise") {
                        host.send(ViewerWebIntentReload.shared)
                    }
                }
            }
        }
        .sheet(item: $safariPage) { page in
            HandoffSafariView(url: page.url)
                .ignoresSafeArea()
        }
    }
}
