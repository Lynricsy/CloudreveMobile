import SwiftUI

/// 搜索 Tab（`Tab(role: .search)`）：系统把搜索栏与 Tab 栏融合
struct SearchTabRoot: View {
    @State private var query = ""

    var body: some View {
        NavigationStack {
            ContentUnavailableView("搜索文件", systemImage: "magnifyingglass", description: Text("按文件名搜索当前账户中的文件。"))
                .navigationTitle("搜索")
                .accessibilityIdentifier("tab.search")
        }
        .searchable(text: $query, prompt: Text("文件名"))
    }
}
