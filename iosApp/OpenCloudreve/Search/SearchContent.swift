import SwiftUI
import OpenCloudreveCore

/// 搜索页：搜索框（系统搜索 Tab）、高级条件、历史、名称/条件结果（游标分页）与全文命中片段；结果支持文件操作
struct SearchContent: View {
    let accountId: String
    @Bindable var search: SearchHost
    let actions: FileActionHost

    @State private var driver = FileActionDriver()
    @State private var editingFilters = false

    var body: some View {
        let state = search.state
        let context = FileActionContext(
            accountId: accountId,
            listingUri: state.searchUri ?? state.baseUri,
            user: CoreGraph.shared.userSettings(accountId: accountId).user.value,
            viewerResolver: nil,
            storagePolicy: nil,
            inSearch: true,
            actions: actions,
            refresh: { [search] in search.send(SearchIntentSubmit.shared) }
        )
        Group {
            if !state.submitted {
                SearchHistoryList(search: search)
            } else if state.loading {
                ProgressView()
                    .controlSize(.large)
            } else if let error = state.error, state.results.isEmpty, state.fullTextHits.isEmpty {
                ContentUnavailableView {
                    Label("搜索失败", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(ErrorText.message(error))
                } actions: {
                    Button("重试") { search.send(SearchIntentSubmit.shared) }
                        .buttonStyle(.glass)
                }
            } else if state.fullText {
                SearchFullTextList(search: search, context: context, driver: driver)
            } else if state.results.isEmpty {
                ContentUnavailableView.search(text: state.query)
            } else {
                SearchResultsList(search: search, context: context, driver: driver)
            }
        }
        .searchable(text: $search.query, prompt: Text("搜索文件名"))
        .onSubmit(of: .search) {
            search.send(SearchIntentSubmit.shared)
        }
        .onChange(of: state.query) { _, query in
            // 清空搜索框回到历史页
            if query.isEmpty && state.submitted { search.send(SearchIntentClear.shared) }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("搜索条件", systemImage: state.filters.isEmpty ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill") {
                    editingFilters = true
                }
                .accessibilityIdentifier("search.filters")
            }
        }
        .sheet(isPresented: $editingFilters) {
            SearchFiltersSheet(search: search)
        }
        .fileActions(driver: driver, actions: actions, context: context) { _, _ in
            search.send(SearchIntentSubmit.shared)
        }
    }
}
