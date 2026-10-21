import SwiftUI
import OpenCloudreveCore

/// 任务详情（推入任务 Tab 的导航栈）：状态、阶段时间线、进度、基本信息、错误、离线下载文件选择与取消。
/// 数据来自 `state.detail`；返回时 `isDetailPresented` 置假 → `CloseDetail`。
struct WorkflowTaskDetailView: View {
    let host: WorkflowListHost

    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false
    @State private var isCancelConfirming = false

    var body: some View {
        Group {
            if let detail = host.state.detail {
                content(detail, busy: host.state.busyIds.contains(detail.row.id))
            } else {
                Color.clear
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("取消此任务？", isPresented: $isCancelConfirming, titleVisibility: .visible) {
            Button("取消任务", role: .destructive) {
                if let id = host.state.detail?.row.id {
                    host.send(WorkflowListIntentCancel(id: id))
                }
            }
            Button("不取消", role: .cancel) {}
        } message: {
            Text("取消后任务会立即停止，无法恢复。")
        }
        .workflowListEffects(host)
        .onAppear {
            isVisible = true
            host.send(WorkflowListIntentSetForeground(foreground: scenePhase == .active))
        }
        .onDisappear {
            isVisible = false
            // 返回列表时详情已关闭，由列表页接管；切换 Tab 等离开时停止轮询
            if host.state.detail != nil {
                host.send(WorkflowListIntentSetForeground(foreground: false))
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if isVisible {
                host.send(WorkflowListIntentSetForeground(foreground: phase == .active))
            }
        }
    }

    private func content(_ detail: WorkflowDetail, busy: Bool) -> some View {
        let row = detail.row
        let task = row.task
        let steps = WorkflowTaskStep.steps(for: task)
        return List {
            Section {
                LabeledContent("状态") {
                    Text(WorkflowText.statusLabel(row.status))
                        .foregroundStyle(WorkflowText.statusColor(row.status))
                        .accessibilityIdentifier("workflow.detail.status")
                }
                if let title = WorkflowText.title(row) {
                    LabeledContent("名称") {
                        Text(verbatim: title)
                            .lineLimit(2)
                            .truncationMode(.middle)
                    }
                }
            }

            if !steps.isEmpty {
                Section("阶段") {
                    WorkflowPhaseTimeline(task: task, steps: steps)
                }
            } else if let phase = row.phase {
                Section {
                    LabeledContent("阶段") {
                        Text(verbatim: phase)
                            .accessibilityIdentifier("workflow.detail.phase")
                    }
                }
            }

            if !detail.progress.isEmpty {
                Section("进度") {
                    ForEach(detail.progress.keys.sorted(), id: \.self) { key in
                        if let progress = detail.progress[key] {
                            progressRow(key: key, progress: progress)
                        }
                    }
                }
            }

            Section("基本信息") {
                LabeledContent("类型") {
                    Text(WorkflowText.typeLabel(row.type))
                }
                ForEach(row.sources, id: \.self) { source in
                    LabeledContent("源") {
                        Text(verbatim: WorkflowText.uriPath(source))
                            .lineLimit(3)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                    }
                }
                if let destination = row.destination {
                    LabeledContent("目标") {
                        Text(verbatim: WorkflowText.uriPath(destination))
                            .lineLimit(3)
                            .truncationMode(.middle)
                    }
                }
                if let policyId = row.dstPolicyId {
                    LabeledContent("目标存储策略") {
                        Text(verbatim: policyId)
                    }
                }
                if let node = task.node {
                    LabeledContent("处理节点") {
                        Text(verbatim: node.name.isEmpty ? node.id : node.name)
                    }
                }
                if let created = FileFormat.dateTime(task.createdAt) {
                    LabeledContent("创建时间") {
                        Text(verbatim: created)
                    }
                }
                if let updated = FileFormat.dateTime(task.updatedAt) {
                    LabeledContent("更新时间") {
                        Text(verbatim: updated)
                    }
                }
                if task.duration > 0 {
                    LabeledContent("已执行") {
                        Text(verbatim: WorkflowText.duration(task.duration))
                    }
                }
                if task.retryCount > 0 {
                    LabeledContent("重试次数") {
                        Text(verbatim: "\(task.retryCount)")
                    }
                }
            }

            if let error = task.error, !error.isEmpty {
                Section("错误") {
                    Text(verbatim: error)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("workflow.detail.error")
                }
            }
            if !task.errorHistory.isEmpty {
                Section("错误记录") {
                    ForEach(Array(task.errorHistory.enumerated()), id: \.offset) { _, message in
                        Text(verbatim: message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
            }

            if let download = row.download {
                downloadStatus(download)
                if !download.files.isEmpty {
                    WorkflowDownloadFilesSection(
                        taskId: row.id,
                        files: download.files,
                        canSelect: row.canSelectFiles,
                        busy: busy
                    ) { selection in
                        host.send(WorkflowListIntentSelectFiles(id: row.id, files: selection))
                    }
                }
            }

            if row.canCancel {
                Section {
                    Button("取消任务", role: .destructive) {
                        isCancelConfirming = true
                    }
                    .disabled(busy)
                    .accessibilityIdentifier("workflow.detail.cancel")
                }
            }
        }
        .navigationTitle(Text(WorkflowText.typeLabel(row.type)))
        .toolbar {
            if detail.polling || busy {
                ToolbarItem(placement: .topBarTrailing) {
                    ProgressView()
                        .accessibilityLabel(Text("正在更新"))
                }
            }
        }
    }

    private func progressRow(key: String, progress: TaskProgress) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(verbatim: progress.identifier.isEmpty ? key : progress.identifier)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Text(verbatim: "\(FileFormat.bytes(progress.current)) / \(FileFormat.bytes(progress.total))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            if progress.total > 0 {
                ProgressView(value: Double(min(progress.current, progress.total)), total: Double(progress.total))
            }
        }
    }

    @ViewBuilder
    private func downloadStatus(_ download: DownloadStatus) -> some View {
        Section("下载状态") {
            if download.total > 0 {
                LabeledContent("已下载") {
                    Text(verbatim: "\(FileFormat.bytes(download.downloaded)) / \(FileFormat.bytes(download.total))")
                }
                ProgressView(value: Double(min(download.downloaded, download.total)), total: Double(download.total))
            }
            if download.downloadSpeed > 0 {
                LabeledContent("下载速度") {
                    Text(verbatim: WorkflowText.speed(download.downloadSpeed))
                }
            }
            if download.uploadSpeed > 0 {
                LabeledContent("上传速度") {
                    Text(verbatim: WorkflowText.speed(download.uploadSpeed))
                }
            }
            if let message = download.errorMessage, !message.isEmpty {
                ErrorRow(message: message)
            }
        }
    }
}
