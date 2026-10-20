import SwiftUI
import OpenCloudreveCore

/// “文件”Tab：`NavigationSplitView`，侧栏为位置（我的文件、固定项、与我共享、回收站）与容量，
/// 详情为所选位置的目录列表（自带导航栈，子目录在其中推入）。iPhone 上自动折叠为单栏导航。
struct FilesTab: View {
    let account: Account

    @State private var selection: String?
    @State private var iconTheme = FileIconTheme.fallback
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        NavigationSplitView {
            FilesHomeScreen(accountId: account.id, selection: $selection)
        } detail: {
            NavigationStack {
                if let selection {
                    FileListScreen(accountId: account.id, uri: selection)
                        .id(selection)
                } else {
                    ContentUnavailableView("选择一个位置", systemImage: "folder", description: Text("在侧栏中选择要浏览的位置。"))
                }
            }
        }
        .environment(\.fileIconTheme, iconTheme)
        .task {
            if horizontalSizeClass == .regular && selection == nil {
                selection = CrUri.companion.my(uid: nil).description()
            }
            iconTheme = await FileIconLoader.theme(accountId: account.id, siteUrl: account.siteUrl)
        }
    }
}
