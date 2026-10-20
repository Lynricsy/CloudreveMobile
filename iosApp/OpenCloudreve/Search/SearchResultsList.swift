import SwiftUI
import OpenCloudreveCore

/// 名称/条件搜索结果（游标分页，排序固定）；条目可打开、进入目录与执行文件操作
struct SearchResultsList: View {
    let search: SearchHost
    let context: FileActionContext
    let driver: FileActionDriver

    var body: some View {
        let state = search.state
        let thumbnails = FileThumbnailSource(accountId: context.accountId, contextHint: state.contextHint, enabled: true)
        List {
            Section {
                ForEach(state.results, id: \.path) { file in
                    FileItemContainer(
                        file: file,
                        context: context,
                        driver: driver,
                        selecting: false,
                        inGrid: false,
                        onToggleSelection: nil
                    ) {
                        FileRowLabel(file: file, thumbnails: thumbnails, isTrash: false)
                    }
                }
                if state.hasMore {
                    LoadMoreRow(loading: state.loadingMore) {
                        search.send(SearchIntentLoadMore.shared)
                    }
                    .id(state.results.count)
                }
            } header: {
                Text("\(state.results.count) 个结果", comment: "搜索结果数量")
            }
        }
        .listStyle(.plain)
    }
}
