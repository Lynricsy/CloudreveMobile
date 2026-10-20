import SwiftUI
import OpenCloudreveCore

/// 搜索 Tab（`Tab(role: .search)`）：系统把搜索栏与 Tab 栏融合；在“我的文件”中按名称/条件或全文搜索
struct SearchTabRoot: View {
    let account: Account

    @State private var iconTheme = FileIconTheme.fallback

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.searchViewModel(accountId: account.id, baseUri: CrUri.companion.my(uid: nil).description())
            }) { search in
                ViewModelScope({ CoreGraph.shared.fileActionViewModel(accountId: account.id) }) { actions in
                    SearchContent(accountId: account.id, search: search, actions: actions)
                }
            }
            .navigationTitle("搜索")
        }
        .environment(\.fileIconTheme, iconTheme)
        .task {
            iconTheme = await FileIconLoader.theme(accountId: account.id, siteUrl: account.siteUrl)
        }
    }
}
