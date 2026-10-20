import SwiftUI
import OpenCloudreveCore

/// 未提交搜索时：最近搜索（点按再次搜索、左滑删除）与当前条件摘要
struct SearchHistoryList: View {
    let search: SearchHost

    var body: some View {
        let state = search.state
        if state.history.isEmpty {
            ContentUnavailableView("搜索文件", systemImage: "magnifyingglass", description: Text("按文件名搜索“我的文件”，或点按右上角设置更多条件。"))
        } else {
            List {
                Section {
                    ForEach(state.history, id: \.self) { query in
                        Button {
                            search.send(SearchIntentUseHistory(query: query))
                        } label: {
                            Label {
                                Text(verbatim: query)
                                    .foregroundStyle(.primary)
                            } icon: {
                                Image(systemName: "clock.arrow.circlepath")
                            }
                        }
                        .swipeActions {
                            Button("删除", systemImage: "trash", role: .destructive) {
                                search.send(SearchIntentDeleteHistory(query: query))
                            }
                        }
                    }
                } header: {
                    Text("最近搜索")
                } footer: {
                    Button("清除搜索历史") {
                        search.send(SearchIntentClearHistory.shared)
                    }
                    .font(.footnote)
                }
            }
        }
    }
}
