import SwiftUI
import OpenCloudreveCore

@main
struct OpenCloudreveApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// 根视图：后续阶段替换为登录/主界面路由
struct RootView: View {
    var body: some View {
        Text(CoreInfo.shared.NAME)
            .font(.largeTitle)
    }
}
