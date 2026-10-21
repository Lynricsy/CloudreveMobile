import SwiftUI
import OpenCloudreveCore

/// 后台任务列表：分类分段、下拉刷新、分页、空/错误态；点按推入详情；右上角新建离线下载。
/// 页面可见且 App 在前台时 `SetForeground(true)`（“下载中”轮询、详情进度轮询），否则停止。
struct WorkflowTasksContent: View {
    let accountId: String
    @Bindable var host: WorkflowListHost

    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false
    @State private var isCreating = false

    var body: some View {
        let state = host.state
        List {
            Section {
                Picker("任务分类", selection: $host.category) {
                    ForEach(WorkflowListState.companion.CATEGORIES, id: \.self) { category in
                        Text(WorkflowText.categoryLabel(category)).tag(category)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .accessibilityIdentifier("workflow.category")
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            if state.rows.isEmpty {
                placeholder(state)
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(state.rows, id: \.id) { row in
                        Button {
                            host.send(WorkflowListIntentOpenDetail(id: row.id))
                        } label: {
                            WorkflowTaskRowView(row: row)
                        }
                        .accessibilityIdentifier("workflow.task")
                    }
                    if state.hasMore {
                        LoadMoreRow(loading: state.loadingMore) {
                            host.send(WorkflowListIntentLoadMore.shared)
                        }
                    }
                }
            }
        }
        .refreshable {
            host.send(WorkflowListIntentRefresh.shared)
            await waitWhileRefreshing()
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("新建离线下载", systemImage: "plus") {
                    isCreating = true
                }
                .accessibilityIdentifier("workflow.new")
            }
        }
        .navigationDestination(isPresented: $host.isDetailPresented) {
            WorkflowTaskDetailView(host: host)
        }
        .sheet(isPresented: $isCreating) {
            RemoteDownloadSheet(
                accountId: accountId,
                dstUri: CrUri.companion.my(uid: nil).description(),
                torrentUri: nil,
                onCreated: { [host] in
                    // 已在“下载中”时 SetCategory 不会重新加载（列表为空时轮询已停止），需显式刷新
                    if host.state.category == TaskCategory.shared.DOWNLOADING {
                        host.send(WorkflowListIntentRefresh.shared)
                    } else {
                        host.category = TaskCategory.shared.DOWNLOADING
                    }
                }
            )
        }
        .workflowListEffects(host)
        .onAppear {
            isVisible = true
            host.send(WorkflowListIntentSetForeground(foreground: scenePhase == .active))
        }
        .onDisappear {
            isVisible = false
            // 推入详情时列表也会消失：详情仍需轮询，由详情页负责
            if host.state.detail == nil {
                host.send(WorkflowListIntentSetForeground(foreground: false))
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if isVisible {
                host.send(WorkflowListIntentSetForeground(foreground: phase == .active))
            }
        }
    }

    /// 没有任务时：加载中 / 错误（可重试）/ 空
    @ViewBuilder
    private func placeholder(_ state: WorkflowListState) -> some View {
        if let error = state.error {
            ContentUnavailableView {
                Label("无法加载", systemImage: "exclamationmark.triangle")
            } description: {
                Text(ErrorText.message(error))
            } actions: {
                Button("重试") {
                    host.send(WorkflowListIntentRefresh.shared)
                }
                .buttonStyle(.glass)
            }
        } else if state.loading {
            ProgressView()
                .controlSize(.large)
                .padding(.vertical, 48)
        } else {
            ContentUnavailableView {
                Label("没有后台任务", systemImage: "clock.arrow.circlepath")
            } description: {
                switch state.category {
                case TaskCategory.shared.DOWNLOADING:
                    Text("进行中的离线下载会显示在这里。")
                case TaskCategory.shared.DOWNLOADED:
                    Text("已结束的离线下载会显示在这里。")
                default:
                    Text("压缩、解压、转移存储策略等任务会显示在这里。")
                }
            }
        }
    }

    /// 下拉刷新：等到列表结束刷新（最多 15 秒）
    private func waitWhileRefreshing() async {
        let deadline = ContinuousClock.now + .seconds(15)
        while (host.state.refreshing || host.state.loading) && ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(100))
        }
    }
}
