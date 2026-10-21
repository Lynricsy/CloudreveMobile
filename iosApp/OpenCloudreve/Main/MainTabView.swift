import SwiftUI
import OpenCloudreveCore

/// 登录后的主界面。iPhone 为 Liquid Glass 底部 Tab 栏（向下滚动时收起），iPad 可切换为侧栏。
/// 根视图以账户 id 作为视图标识，切换账户时整棵树重建。
struct MainTabView: View {
    @State private var selection: MainTab = .files
    @Environment(AppSession.self) private var session

    var body: some View {
        TabView(selection: $selection) {
            Tab("文件", systemImage: "folder", value: MainTab.files) {
                if let account = session.activeAccount {
                    FilesTab(account: account)
                }
            }
            Tab("分享", systemImage: "square.and.arrow.up", value: MainTab.shares) {
                if let account = session.activeAccount {
                    SharesTab(account: account)
                }
            }
            Tab("任务", systemImage: "arrow.up.arrow.down.circle", value: MainTab.tasks) {
                if let account = session.activeAccount {
                    TasksTab(account: account)
                }
            }
            Tab("我的", systemImage: "person.crop.circle", value: MainTab.me) {
                MeScreen()
            }
            Tab(value: MainTab.search, role: .search) {
                if let account = session.activeAccount {
                    SearchTabRoot(account: account)
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(MainBottomAccessory(accountId: session.activeAccount?.id ?? "", selection: $selection))
        .modifier(ShareLinkDeepLink(accountId: session.activeAccount?.id))
        .onChange(of: session.requestedTab, initial: true) { _, tab in
            guard let tab else { return }
            selection = tab
            session.requestedTab = nil
        }
    }
}
