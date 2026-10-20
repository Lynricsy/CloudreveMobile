import SwiftUI
import OpenCloudreveCore

/// 目录列表页内容：列表/网格、空/错误态、下拉刷新、工具栏（新建、视图菜单、多选）与文件操作
struct FileListContent: View {
    let accountId: String
    @Bindable var list: FileListHost
    let actions: FileActionHost

    @State private var driver = FileActionDriver()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let state = list.state
        let context = FileActionContext(
            accountId: accountId,
            listingUri: state.uri,
            user: state.user,
            viewerResolver: state.viewerResolver,
            storagePolicy: state.storagePolicy,
            inSearch: false,
            actions: actions,
            refresh: { [list] in list.send(FileListIntentRefresh.shared) }
        )
        let thumbnails = FileThumbnailSource(accountId: accountId, contextHint: state.contextHint, enabled: state.showThumbnails)

        Group {
            if state.files.isEmpty {
                FileListPlaceholder(loading: state.loading, error: state.error, fs: state.fs, isSearch: state.isSearch) {
                    list.send(FileListIntentRefresh.shared)
                }
            } else if list.viewMode == ExplorerView.companion.VIEW_LIST {
                FileListRows(list: list, context: context, driver: driver, thumbnails: thumbnails)
            } else {
                FileGridContent(list: list, context: context, driver: driver, thumbnails: thumbnails)
            }
        }
        .navigationSubtitle(subtitle(state))
        .toolbar {
            if state.selecting {
                FileSelectionToolbar(
                    selected: state.selectedFiles,
                    context: context,
                    driver: driver,
                    maxBatchSize: Int(state.maxBatchSize),
                    selectAll: { list.send(FileListIntentSelectAll.shared) },
                    done: { list.send(FileListIntentExitSelection.shared) }
                )
            } else {
                if state.canCreate {
                    ToolbarItem(placement: .topBarTrailing) {
                        FileNewMenu(options: state.viewerResolver?.doNewFileOptions() ?? [], context: context, driver: driver)
                    }
                }
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
                ToolbarItem(placement: .topBarTrailing) {
                    FileViewMenu(list: list, driver: driver)
                }
            }
        }
        .toolbarVisibility(state.selecting ? .hidden : .automatic, for: .tabBar)
        .refreshable {
            list.send(FileListIntentRefresh.shared)
            await waitWhileRefreshing()
        }
        .fileActions(driver: driver, actions: actions, context: context) { operation, _ in
            applyChange(operation)
        }
        .task {
            await list.effects { effect in
                if case let .showError(event) = onEnum(of: effect) {
                    driver.errorMessage = ErrorText.message(event.error)
                }
            }
        }
        .onAppear { list.send(FileListIntentSetForeground(foreground: true)) }
        .onDisappear { list.send(FileListIntentSetForeground(foreground: false)) }
        .onChange(of: scenePhase) { _, phase in
            list.send(FileListIntentSetForeground(foreground: phase == .active))
        }
    }

    private func subtitle(_ state: FileListState) -> String {
        if state.selecting {
            return String(localized: "已选择 \(state.selection.count) 项", comment: "多选模式下的导航副标题")
        }
        if state.files.isEmpty { return "" }
        let count = state.files.count
        return state.hasMore
            ? String(localized: "\(count)+ 项", comment: "目录条目数（还有更多未加载）")
            : String(localized: "\(count) 项", comment: "目录条目数")
    }

    /// 下拉刷新：等到列表结束刷新（最多 15 秒）
    private func waitWhileRefreshing() async {
        let deadline = ContinuousClock.now + .seconds(15)
        while (list.state.refreshing || list.state.loading) && ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

    /// 操作成功后更新列表：移出当前目录的先本地移除（随后自动刷新），其余直接刷新
    private func applyChange(_ operation: FileOperation) {
        if let delete = operation as? FileOperationDelete {
            list.send(FileListIntentRemoveLocally(paths: delete.uris))
        } else if let move = operation as? FileOperationMove {
            list.send(FileListIntentRemoveLocally(paths: move.uris))
        } else if let restore = operation as? FileOperationRestore {
            list.send(FileListIntentRemoveLocally(paths: restore.uris))
        } else {
            list.send(FileListIntentRefresh.shared)
        }
        if list.state.selecting {
            list.send(FileListIntentExitSelection.shared)
        }
    }
}
