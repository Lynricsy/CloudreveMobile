import SwiftUI
import OpenCloudreveCore

@main
struct OpenCloudreveApp: App {
    @State private var session: AppSession

    init() {
        CoreBootstrap.start()
        _session = State(initialValue: AppSession(accounts: CoreGraph.shared.accounts()))
    }

    var body: some Scene {
        WindowGroup {
            RootView(session: session)
        }
    }
}
