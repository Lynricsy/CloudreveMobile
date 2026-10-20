import SwiftUI
import OpenCloudreveCore

/// 管理端列表与详情页共用的事件处理：失败提示、网页后台、文件地址、节点测试、任务清理。
/// 列表页与其推入的详情页各自挂载；只有可见页面的 `.task` 在收集事件。
struct AdminListEffectHandling<T: AnyObject>: ViewModifier {
    let host: AdminListHost<T>
    var onDeleted: () -> Void = {}
    @State private var failure: String?
    @State private var notice: AdminNotice?
    @State private var webPage: HandoffPage?

    func body(content: Content) -> some View {
        content
            .task {
                await host.effects { effect in
                    handle(effect)
                }
            }
            .errorMessageAlert(message: $failure)
            .alert(Text(verbatim: notice?.title ?? ""), isPresented: isShowingNotice, presenting: notice) { notice in
                if let url = notice.url {
                    Button("拷贝") { UIPasteboard.general.string = url.absoluteString }
                    Button("打开") { webPage = HandoffPage(url: url) }
                }
                Button("好", role: .cancel) {}
            } message: { notice in
                Text(verbatim: notice.message)
            }
            .sheet(item: $webPage) { page in
                HandoffSafariView(url: page.url)
                    .ignoresSafeArea()
            }
    }

    private func handle(_ effect: any AdminListEffect) {
        switch onEnum(of: effect) {
        case .deleted:
            onDeleted()
        case let .failed(failed):
            failure = ErrorText.message(failed.error)
        case let .openUrl(open):
            if let url = URL(string: open.url) {
                webPage = HandoffPage(url: url)
            }
        case let .fileUrlReady(ready):
            notice = AdminNotice(title: String(localized: "文件地址（1 小时内有效）"), message: ready.url, url: URL(string: ready.url))
        case let .nodeTestResult(result):
            notice = AdminNotice(
                title: result.success ? String(localized: "节点连接正常") : String(localized: "节点连接失败"),
                message: result.message ?? ""
            )
        case .tasksCleaned:
            notice = AdminNotice(title: String(localized: "已清理旧任务"), message: "")
        }
    }

    private var isShowingNotice: Binding<Bool> {
        Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })
    }
}
