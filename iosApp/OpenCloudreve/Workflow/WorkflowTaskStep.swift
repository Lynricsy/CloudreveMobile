import Foundation
import OpenCloudreveCore

/// 任务阶段时间线的一步；`phase` 为 `summary.phase` 的取值（空串表示由位置推断）。
/// 与网页端 `TaskProgress.tsx` 的 stepOptions 一致：主节点 / 从节点两套。
struct WorkflowTaskStep {
    let title: LocalizedStringResource
    var phase = ""

    private static let queued = WorkflowTaskStep(title: "排队等待")
    private static let finished = WorkflowTaskStep(title: "完成")

    private static let masterSteps: [String: [WorkflowTaskStep]] = [
        TaskType.shared.REMOTE_DOWNLOAD: [
            queued,
            WorkflowTaskStep(title: "下载", phase: "monitor"),
            WorkflowTaskStep(title: "转存", phase: "transfer"),
            WorkflowTaskStep(title: "等待做种", phase: "seeding"),
            finished,
        ],
        TaskType.shared.IMPORT: [queued, WorkflowTaskStep(title: "导入文件")],
        TaskType.shared.RELOCATE: [
            queued,
            WorkflowTaskStep(title: "索引文件"),
            WorkflowTaskStep(title: "转存", phase: "transfer"),
            WorkflowTaskStep(title: "提交更改", phase: "finish"),
        ],
        TaskType.shared.EXTRACT_ARCHIVE: [
            queued,
            WorkflowTaskStep(title: "下载压缩包", phase: "download_zip"),
            WorkflowTaskStep(title: "解压文件"),
        ],
        TaskType.shared.CREATE_ARCHIVE: [
            queued,
            WorkflowTaskStep(title: "准备"),
            WorkflowTaskStep(title: "压缩文件", phase: "compress_files"),
            WorkflowTaskStep(title: "转存", phase: "upload_archive"),
        ],
        TaskType.shared.GDRIVE_RESTORE: [queued, WorkflowTaskStep(title: "恢复文件"), finished],
    ]

    private static let slaveSteps: [String: [WorkflowTaskStep]] = [
        TaskType.shared.EXTRACT_ARCHIVE: [
            queued,
            WorkflowTaskStep(title: "发送任务"),
            WorkflowTaskStep(title: "解压文件", phase: "await_slave_complete"),
        ],
        TaskType.shared.CREATE_ARCHIVE: [
            queued,
            WorkflowTaskStep(title: "索引文件"),
            WorkflowTaskStep(title: "压缩文件", phase: "await_slave_compressing"),
            WorkflowTaskStep(title: "转存", phase: "await_slave_uploading"),
            WorkflowTaskStep(title: "提交更改", phase: "complete_upload"),
        ],
    ]

    /// 任务的阶段列表（未知类型为空）
    static func steps(for task: WorkflowTask) -> [WorkflowTaskStep] {
        if task.node?.type == "slave", let steps = slaveSteps[task.type] { return steps }
        return masterSteps[task.type] ?? []
    }

    /// 当前所在步骤：排队 0；完成 = steps.count；否则按 phase 匹配（缺省第 1 步）
    static func activeIndex(task: WorkflowTask, steps: [WorkflowTaskStep]) -> Int {
        switch task.status {
        case TaskStatus.shared.QUEUED:
            return 0
        case TaskStatus.shared.COMPLETED:
            return steps.count
        default:
            var active = 1
            let phase = task.summary?.phase
            for index in steps.indices.dropFirst() where !steps[index].phase.isEmpty && steps[index].phase == phase {
                active = index
            }
            return min(active, max(steps.count - 1, 0))
        }
    }
}
