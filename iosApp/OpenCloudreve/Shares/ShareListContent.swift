import SwiftUI
import UIKit
import OpenCloudreveCore

/// 我的分享列表内容：空/错误态、下拉刷新、滚动分页、排序、多选批量删除、行操作（复制链接、二维码、编辑、前往源文件、删除）
struct ShareListContent: View {
    let accountId: String
    @Bindable var host: ShareListHost

    /// 点按“选择”进入的多选模式（VM 只在已有选中项时标记 `selecting`）
    @State private var selectMode = false
    @State private var sheet: ShareListSheet?
    /// “前往源文件”推入的目录 URI
    @State private var sourceFolder: String?
    /// 待确认删除的分享 id
    @State private var pendingDelete: [String]?
    @State private var failure: String?
    @State private var copyCount = 0
    @State private var appeared = false

    var body: some View {
        let state = host.state
        let selecting = selectMode || state.selecting

        Group {
            if state.shares.isEmpty {
                ShareListPlaceholder(loading: state.loading, error: state.error) {
                    host.send(ShareListIntentRefresh.shared)
                }
            } else {
                // 只在多选模式下绑定选择：否则 List 会把点按行当成“选中”
                List(selection: selecting ? $host.selectionIds : nil) {
                    ForEach(state.shares, id: \.id) { share in
                        ShareRowItem(
                            share: share,
                            selecting: selecting,
                            onToggleSelection: { host.send(ShareListIntentToggleSelection(id: share.id)) },
                            perform: { action in perform(action, on: share) }
                        )
                    }
                    if state.hasMore {
                        LoadMoreRow(loading: state.loadingMore) {
                            host.send(ShareListIntentLoadMore.shared)
                        }
                        // 每加载一页换一次标识：尾行仍在屏幕内时也会再次触发
                        .id(state.shares.count)
                        .selectionDisabled()
                    }
                }
                .listStyle(.plain)
                .environment(\.editMode, .constant(selecting ? .active : .inactive))
            }
        }
        .navigationSubtitle(subtitle(state, selecting: selecting))
        .toolbar {
            ShareListToolbar(
                host: host,
                selecting: selecting,
                enterSelection: { selectMode = true },
                exitSelection: {
                    selectMode = false
                    host.send(ShareListIntentExitSelection.shared)
                },
                deleteSelected: { pendingDelete = Array(host.state.selection) }
            )
        }
        .toolbarVisibility(selecting ? .hidden : .automatic, for: .tabBar)
        .refreshable {
            host.send(ShareListIntentRefresh.shared)
            await waitWhileRefreshing()
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case let .edit(shareId):
                ShareEditSheet(accountId: accountId, fileUri: nil, shareId: shareId, onSaved: { [host] in
                    host.send(ShareListIntentRefresh.shared)
                })
            case let .qr(link, name):
                ShareQRCodeSheet(link: link, name: name)
            }
        }
        .navigationDestination(item: $sourceFolder) { uri in
            FileListScreen(accountId: accountId, uri: uri)
        }
        .confirmationDialog(
            Text("删除 \(pendingDelete?.count ?? 0) 个分享？", comment: "删除分享确认标题，参数为数量"),
            isPresented: isConfirmingDelete,
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { ids in
            Button("删除", role: .destructive) {
                host.send(ShareListIntentDelete(ids: ids))
            }
        } message: { _ in
            Text("删除后链接将立即失效，源文件不受影响。")
        }
        .errorMessageAlert(message: $failure)
        .sensoryFeedback(.success, trigger: copyCount)
        .task {
            await host.effects { effect in
                handle(effect)
            }
        }
        .onAppear {
            // 再次回到此页（切换 Tab、从源文件目录返回）时刷新：分享可能在“文件”Tab 中新建
            if appeared { host.send(ShareListIntentRefresh.shared) }
            appeared = true
        }
    }

    private func perform(_ action: ShareRowAction, on share: Share) {
        switch action {
        case .copy:
            UIPasteboard.general.string = share.url
            copyCount += 1
        case .qr:
            sheet = .qr(link: share.url, name: ShareFormat.title(share))
        case .edit:
            sheet = .edit(shareId: share.id)
        case .source:
            host.send(ShareListIntentOpenSource(id: share.id))
        case .delete:
            pendingDelete = [share.id]
        }
    }

    private func handle(_ effect: any ShareListEffect) {
        switch onEnum(of: effect) {
        case let .deleted(deleted):
            if host.state.selection.isEmpty { selectMode = false }
            let message = String(localized: "已删除 \(Int(deleted.count)) 个分享", comment: "删除分享后的读屏播报，参数为数量")
            AccessibilityNotification.Announcement(message).post()
        case let .openSource(open):
            let isFolder = host.state.shares.first { $0.id == open.shareId }?.isFolder ?? false
            sourceFolder = ShareFormat.sourceFolder(sourceUri: open.sourceUri, isFolder: isFolder)
        case let .failed(failed):
            failure = ErrorText.message(failed.error)
        }
    }

    private func subtitle(_ state: ShareListState, selecting: Bool) -> String {
        if selecting {
            return String(localized: "已选择 \(state.selection.count) 项", comment: "多选模式下的导航副标题")
        }
        if state.shares.isEmpty { return "" }
        let count = state.shares.count
        return state.hasMore
            ? String(localized: "\(count)+ 项", comment: "目录条目数（还有更多未加载）")
            : String(localized: "\(count) 项", comment: "目录条目数")
    }

    /// 下拉刷新：等到列表结束刷新（最多 15 秒；离开页面时立即结束）
    private func waitWhileRefreshing() async {
        await RefreshWait.whileBusy { host.state.refreshing || host.state.loading }
    }

    private var isConfirmingDelete: Binding<Bool> {
        Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
    }
}
