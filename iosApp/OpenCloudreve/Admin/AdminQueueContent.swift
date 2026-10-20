import SwiftUI
import OpenCloudreveCore

struct AdminQueueContent: View {
    let host: AdminListHost<AdminTask>
    @State private var metrics: [QueueMetric] = []
    @State private var cleanupDays: Int?

    private static let statuses = ["queued", "processing", "suspending", "error", "canceled", "completed"]

    var body: some View {
        AdminEntityList(
            host: host,
            supportsDelete: true,
            searchFields: [
                AdminSearchField(key: AdminConditions.shared.TASK_USER_ID, title: "用户 ID"),
                AdminSearchField(key: AdminConditions.shared.TASK_CORRELATION_ID, title: "关联 ID"),
            ],
            idOf: { $0.id },
            header: {
                if !metrics.isEmpty {
                    AdminQueueMetricsSection(metrics: metrics)
                }
            },
            filters: {
                AdminConditionPicker(
                    host: host,
                    key: AdminConditions.shared.TASK_STATUS,
                    title: "状态",
                    options: Self.statuses.map { AdminOption(value: $0, label: AdminLabels.taskStatus($0)) }
                )
                Menu("清理旧任务", systemImage: "trash.slash") {
                    Button("7 天前结束的任务") { cleanupDays = 7 }
                    Button("30 天前结束的任务") { cleanupDays = 30 }
                    Button("全部已结束的任务") { cleanupDays = 0 }
                }
                .disabled(host.state.busyId?.int32Value == AdminQueueViewModel.companion.CLEANUP_BUSY_ID)
            },
            row: { AdminTaskRow(task: $0) },
            destination: { task in
                AdminDetailContainer(host: host, id: task.id, idOf: { $0.id }) { detail in
                    AdminTaskDetail(task: detail, deleting: host.state.deleting) {
                        host.send(AdminListIntentDelete(ids: [KotlinInt(int: detail.id)]))
                    }
                }
                .navigationTitle("任务详情")
            }
        )
        .confirmationDialog("清理旧任务？", isPresented: isConfirmingCleanup, titleVisibility: .visible, presenting: cleanupDays) { days in
            Button("清理", role: .destructive) { cleanup(olderThanDays: days) }
        } message: { _ in
            Text("将删除已完成、出错和已取消的任务记录。")
        }
        .task {
            guard let viewModel = host.viewModel as? AdminQueueViewModel else { return }
            for await value in viewModel.metrics {
                metrics = value
            }
        }
    }

    private func cleanup(olderThanDays days: Int) {
        let notAfter = Date.now.addingTimeInterval(-Double(days) * 86_400)
        host.send(AdminListIntentCleanupTasks(notAfterEpochSeconds: Int64(notAfter.timeIntervalSince1970), types: [], statuses: []))
    }

    private var isConfirmingCleanup: Binding<Bool> {
        Binding(get: { cleanupDays != nil }, set: { if !$0 { cleanupDays = nil } })
    }
}
