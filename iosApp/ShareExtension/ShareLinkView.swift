import SwiftUI
import UIKit

/// 分享的是 OpenCloudreve 分享链接：转交主 App 打开（`opencloudreve://open?url=`）；
/// 宿主不允许扩展打开 App 时复制链接并提示在 App 中粘贴
struct ShareLinkView: View {
    let session: ShareSession
    let link: String

    @State private var copied = false

    var body: some View {
        Form {
            Section {
                Label("这是一个 OpenCloudreve 分享链接", systemImage: "link")
                Text(verbatim: link)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Section {
                PrimaryActionButton(title: "在 App 中打开") {
                    Task { await openInApp() }
                }
            }
            if copied {
                Section {
                    Text("已复制链接。打开 OpenCloudreve，在“文件”页选择“打开分享链接”即可粘贴打开。")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(copied ? LocalizedStringKey("完成") : LocalizedStringKey("取消")) {
                    if copied { session.complete() } else { session.cancel() }
                }
            }
        }
    }

    private func openInApp() async {
        var components = URLComponents()
        components.scheme = "opencloudreve"
        components.host = "open"
        components.queryItems = [URLQueryItem(name: "url", value: link)]
        if let url = components.url, await session.open(url) {
            session.complete()
            return
        }
        UIPasteboard.general.string = link
        copied = true
    }
}
