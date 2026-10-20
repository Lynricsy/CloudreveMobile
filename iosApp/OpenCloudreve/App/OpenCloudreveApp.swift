import SwiftUI
import OpenCloudreveCore

@main
struct OpenCloudreveApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var session: AppSession

    init() {
        CoreBootstrap.start()
        // 安装后台传输执行器并接管上次未完成的传输（含分享扩展留下的会话）
        BackgroundTransferExecutor.install(role: .app)
        CoreGraph.shared.transferManager().reattach()
        _session = State(initialValue: AppSession(accounts: CoreGraph.shared.accounts()))
        FileProviderDomains.start(accounts: CoreGraph.shared.accounts())
    }

    var body: some Scene {
        WindowGroup {
            RootView(session: session)
                .environment(session)
                .preferredColorScheme(UITestAppearance.colorScheme)
        }
        .commands { AppCommands() }
    }
}
