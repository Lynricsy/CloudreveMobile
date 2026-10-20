import SwiftUI
import OpenCloudreveCore

/// 列表模式：系统 `List`（多选走 `List(selection:)` + 编辑模式），滚到底自动加载下一页
struct FileListRows: View {
    @Bindable var list: FileListHost
    let context: FileActionContext
    let driver: FileActionDriver
    let thumbnails: FileThumbnailSource

    var body: some View {
        let state = list.state
        // 只在多选模式下绑定选择：否则 List 会把点按目录行当成“选中”而不是进入
        List(selection: state.selecting ? $list.selectionPaths : nil) {
            ForEach(state.files, id: \.path) { file in
                FileItemContainer(
                    file: file,
                    context: context,
                    driver: driver,
                    selecting: state.selecting,
                    inGrid: false,
                    onToggleSelection: { list.send(FileListIntentToggleSelection(path: file.path)) }
                ) {
                    FileRowLabel(file: file, thumbnails: thumbnails, isTrash: state.isTrash)
                }
            }
            if state.hasMore {
                LoadMoreRow(loading: state.loadingMore) {
                    list.send(FileListIntentLoadMore.shared)
                }
                // 每加载一页换一次标识：尾行仍在屏幕内时也会再次触发
                .id(state.files.count)
                .selectionDisabled()
            }
        }
        .listStyle(.plain)
        .environment(\.editMode, .constant(state.selecting ? .active : .inactive))
    }
}
