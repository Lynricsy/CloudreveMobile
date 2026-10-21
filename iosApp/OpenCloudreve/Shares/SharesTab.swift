import SwiftUI
import OpenCloudreveCore

/// “分享”Tab：我的分享列表（自带导航栈；“前往源文件”在栈内推入目录列表）
struct SharesTab: View {
    let account: Account

    @State private var iconTheme = FileIconTheme.fallback

    var body: some View {
        NavigationStack {
            ShareListScreen(accountId: account.id)
                .id(account.id)
        }
        // 推入的目录列表显示文件图标
        .environment(\.fileIconTheme, iconTheme)
        .task(id: account.id) {
            iconTheme = await FileIconLoader.theme(accountId: account.id, siteUrl: account.siteUrl)
        }
    }
}
