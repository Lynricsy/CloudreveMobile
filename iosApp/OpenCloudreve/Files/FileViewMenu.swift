import SwiftUI
import OpenCloudreveCore

/// 列表右上角“更多”菜单：进入多选、列表/网格、排序、缩略图开关、清空回收站
struct FileViewMenu: View {
    @Bindable var list: FileListHost
    let driver: FileActionDriver

    var body: some View {
        let state = list.state
        Menu {
            if !state.files.isEmpty {
                Button("选择", systemImage: "checkmark.circle") {
                    list.send(FileListIntentEnterSelection.shared)
                }
                .accessibilityIdentifier("files.select")
            }
            Picker("视图", selection: $list.viewMode) {
                Label("列表", systemImage: "list.bullet").tag(ExplorerView.companion.VIEW_LIST)
                Label("网格", systemImage: "square.grid.2x2").tag(ExplorerView.companion.VIEW_GRID)
            }
            .pickerStyle(.inline)
            if state.canSort {
                Section {
                    Picker(selection: $list.orderBy) {
                        ForEach(state.orderByOptions, id: \.self) { option in
                            Text(Self.orderByTitle(option)).tag(option)
                        }
                    } label: {
                        Label("排序方式", systemImage: "arrow.up.arrow.down")
                    }
                    .pickerStyle(.menu)
                    if !state.orderDirectionOptions.isEmpty {
                        Picker(selection: $list.orderDirection) {
                            ForEach(state.orderDirectionOptions, id: \.self) { option in
                                Text(Self.directionTitle(option)).tag(option)
                            }
                        } label: {
                            Label("顺序", systemImage: "arrow.up.and.down.text.horizontal")
                        }
                        .pickerStyle(.menu)
                    }
                }
            }
            Toggle(isOn: $list.showThumbnails) {
                Label("显示缩略图", systemImage: "photo")
            }
            if state.isTrash && !state.files.isEmpty {
                Section {
                    Button("清空回收站", systemImage: "trash.slash", role: .destructive) {
                        driver.confirmingEmptyTrash = true
                    }
                    .accessibilityIdentifier("files.emptyTrash")
                }
            }
        } label: {
            Label("更多", systemImage: "ellipsis")
        }
        .accessibilityIdentifier("files.more")
    }

    static func orderByTitle(_ option: String) -> LocalizedStringKey {
        switch option {
        case FileOrderBy.shared.NAME: "名称"
        case FileOrderBy.shared.SIZE: "大小"
        case FileOrderBy.shared.UPDATED_AT: "修改时间"
        case FileOrderBy.shared.CREATED_AT: "创建时间"
        default: LocalizedStringKey(option)
        }
    }

    static func directionTitle(_ option: String) -> LocalizedStringKey {
        option == OrderDirection.shared.DESC ? "降序" : "升序"
    }
}
