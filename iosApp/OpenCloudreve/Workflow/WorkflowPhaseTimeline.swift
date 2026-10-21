import SwiftUI
import OpenCloudreveCore

/// 任务阶段时间线（放在 `Section` 内）：已过阶段打勾，当前阶段高亮。
/// 当前阶段文字带 `workflow.detail.phase`（已完成的任务为最后一步）。
struct WorkflowPhaseTimeline: View {
    let task: WorkflowTask
    let steps: [WorkflowTaskStep]

    var body: some View {
        let active = WorkflowTaskStep.activeIndex(task: task, steps: steps)
        let completed = task.status == TaskStatus.shared.COMPLETED
        let identified = min(active, steps.count - 1)
        ForEach(steps.indices, id: \.self) { index in
            let isCurrent = index == active && !completed
            let isDone = index < active
            HStack(spacing: 12) {
                indicator(isCurrent: isCurrent, isDone: isDone)
                    .frame(width: 22)
                Text(steps[index].title)
                    .fontWeight(isCurrent ? .semibold : .regular)
                    .foregroundStyle(isCurrent || isDone ? .primary : .secondary)
                    .accessibilityIdentifier(index == identified ? "workflow.detail.phase" : "")
            }
        }
    }

    @ViewBuilder
    private func indicator(isCurrent: Bool, isDone: Bool) -> some View {
        if isDone {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .accessibilityLabel(Text("已过阶段"))
        } else if isCurrent {
            switch task.status {
            case TaskStatus.shared.ERROR:
                Image(systemName: "exclamationmark.circle.fill")
                    .foregroundStyle(.red)
                    .accessibilityLabel(Text("在此阶段失败"))
            case TaskStatus.shared.CANCELED:
                Image(systemName: "xmark.circle")
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(Text("在此阶段取消"))
            default:
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel(Text("当前阶段"))
            }
        } else {
            Image(systemName: "circle")
                .foregroundStyle(.tertiary)
                .accessibilityLabel(Text("未开始"))
        }
    }
}
