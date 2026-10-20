import SwiftUI
import OpenCloudreveCore

/// 各队列的工作者与任务计数
struct AdminQueueMetricsSection: View {
    let metrics: [QueueMetric]

    var body: some View {
        Section("队列") {
            ForEach(metrics, id: \.name) { metric in
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: AdminLabels.queueName(metric.name))
                        .font(.headline)
                    Text("工作中 \(Int(metric.busyWorkers)) · 已提交 \(Int(metric.submittedTasks)) · 成功 \(Int(metric.successTasks)) · 失败 \(Int(metric.failureTasks)) · 挂起 \(Int(metric.suspendingTasks))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}
