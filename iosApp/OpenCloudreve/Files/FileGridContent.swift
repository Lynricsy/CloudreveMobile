import SwiftUI
import OpenCloudreveCore

/// 网格模式：自适应列宽的 `LazyVGrid`；多选时点按切换选中
struct FileGridContent: View {
    let list: FileListHost
    let context: FileActionContext
    let driver: FileActionDriver
    let thumbnails: FileThumbnailSource

    @ScaledMetric(relativeTo: .body) private var minimumWidth: CGFloat = 108

    var body: some View {
        let state = list.state
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: minimumWidth), spacing: 8, alignment: .top)], spacing: 12) {
                ForEach(state.files, id: \.path) { file in
                    FileItemContainer(
                        file: file,
                        context: context,
                        driver: driver,
                        selecting: state.selecting,
                        inGrid: true,
                        onToggleSelection: { list.send(FileListIntentToggleSelection(path: file.path)) }
                    ) {
                        FileGridCell(
                            file: file,
                            thumbnails: thumbnails,
                            selecting: state.selecting,
                            selected: state.selection.contains(file.path)
                        )
                    }
                }
            }
            .padding(.horizontal)
            if state.hasMore {
                LoadMoreRow(loading: state.loadingMore) {
                    list.send(FileListIntentLoadMore.shared)
                }
                // 每加载一页换一次标识：尾行仍在屏幕内时也会再次触发
                .id(state.files.count)
            }
        }
    }
}
