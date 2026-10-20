import SwiftUI
import OpenCloudreveCore

/// 登录后的主界面。iPhone 为 Liquid Glass 底部 Tab 栏（向下滚动时收起），iPad 可切换为侧栏。
/// 根视图以账户 id 作为视图标识，切换账户时整棵树重建。
struct MainTabView: View {
    @State private var selection: MainTab = .files

    var body: some View {
        TabView(selection: $selection) {
            Tab("文件", systemImage: "folder", value: MainTab.files) {
                TabPlaceholder(
                    title: "文件",
                    systemImage: "folder",
                    message: "你的文件和文件夹会显示在这里。",
                    identifier: "tab.files"
                )
            }
            Tab("分享", systemImage: "square.and.arrow.up", value: MainTab.shares) {
                TabPlaceholder(
                    title: "分享",
                    systemImage: "square.and.arrow.up",
                    message: "你创建的分享链接会显示在这里。",
                    identifier: "tab.shares"
                )
            }
            Tab("任务", systemImage: "arrow.up.arrow.down.circle", value: MainTab.tasks) {
                TabPlaceholder(
                    title: "任务",
                    systemImage: "arrow.up.arrow.down.circle",
                    message: "上传、下载与后台任务会显示在这里。",
                    identifier: "tab.tasks"
                )
            }
            Tab("我的", systemImage: "person.crop.circle", value: MainTab.me) {
                MeScreen()
            }
            Tab(value: MainTab.search, role: .search) {
                SearchTabRoot()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabBarMinimizeBehavior(.onScrollDown)
    }
}
