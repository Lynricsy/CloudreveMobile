import SwiftUI
import OpenCloudreveCore

/// 全文搜索命中：文件名 + 命中片段（偏移分页）
struct SearchFullTextList: View {
    let search: SearchHost
    let context: FileActionContext
    let driver: FileActionDriver

    var body: some View {
        let state = search.state
        let thumbnails = FileThumbnailSource(accountId: context.accountId, contextHint: state.contextHint, enabled: true)
        List {
            Section {
                if state.fullTextHits.isEmpty {
                    Text("没有找到包含该内容的文件")
                        .foregroundStyle(.secondary)
                }
                ForEach(state.fullTextHits, id: \.file.path) { hit in
                    FileItemContainer(
                        file: hit.file,
                        context: context,
                        driver: driver,
                        selecting: false,
                        inGrid: false,
                        onToggleSelection: nil
                    ) {
                        VStack(alignment: .leading, spacing: 4) {
                            FileRowLabel(file: hit.file, thumbnails: thumbnails, isTrash: false)
                            if !hit.content.isEmpty {
                                Text(verbatim: hit.content)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(3)
                            }
                        }
                    }
                }
                if state.hasMore {
                    LoadMoreRow(loading: state.loadingMore) {
                        search.send(SearchIntentLoadMore.shared)
                    }
                    .id(state.fullTextHits.count)
                }
            } header: {
                Text("约 \(Int(state.fullTextTotal)) 个文件包含该内容", comment: "全文搜索命中数量")
            }
        }
        .listStyle(.plain)
    }
}
