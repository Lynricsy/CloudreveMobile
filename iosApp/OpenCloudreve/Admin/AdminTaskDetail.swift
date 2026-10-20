import SwiftUI
import OpenCloudreveCore

/// 任务详情：基本信息、错误、公开状态与内部状态（JSON 折叠）
struct AdminTaskDetail: View {
    let task: AdminTask
    let deleting: Bool
    let delete: () -> Void
    @State private var confirmingDelete = false

    var body: some View {
        Form {
            Section("信息") {
                LabeledContent("类型") { Text(verbatim: AdminLabels.taskType(task.type)) }
                LabeledContent("状态") { Text(verbatim: AdminLabels.taskStatus(task.status)) }
                if let user = task.edges.user {
                    LabeledContent("用户") { Text(verbatim: "\(user.nick) (\(user.email))") }
                }
                if let node = task.node {
                    LabeledContent("节点") { Text(verbatim: node.name) }
                }
                if let phase = task.summary?.phase, !phase.isEmpty {
                    LabeledContent("阶段") { Text(verbatim: phase) }
                }
                if let created = SettingsFormat.dateTime(task.createdAt) {
                    LabeledContent("创建时间") { Text(verbatim: created) }
                }
                if let updated = SettingsFormat.dateTime(task.updatedAt) {
                    LabeledContent("更新时间") { Text(verbatim: updated) }
                }
                if let correlation = task.correlationId, !correlation.isEmpty {
                    LabeledContent("关联 ID") {
                        Text(verbatim: correlation)
                            .textSelection(.enabled)
                    }
                }
            }

            if let state = task.publicState {
                Section("执行") {
                    LabeledContent("执行耗时") {
                        Text(Duration.nanoseconds(state.executedDuration), format: .units(allowed: [.hours, .minutes, .seconds], width: .abbreviated))
                    }
                    LabeledContent("重试次数") { Text(Int(state.retryCount), format: .number) }
                    if let error = state.error, !error.isEmpty {
                        Text(verbatim: error)
                            .foregroundStyle(.red)
                            .textSelection(.enabled)
                    }
                    if !state.errorHistory.isEmpty {
                        DisclosureGroup("错误历史（\(state.errorHistory.count)）") {
                            Text(verbatim: state.errorHistory.joined(separator: "\n\n"))
                                .font(.caption)
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            Section("原始数据") {
                if let props = task.summary?.props, !props.isEmpty {
                    AdminJSONDisclosure(title: "摘要", json: AdminJSON.object(props))
                }
                if let slave = task.publicState?.slaveTaskProps {
                    AdminJSONDisclosure(title: "从机任务属性", json: AdminJSON.pretty(String(describing: slave)))
                }
                if let raw = task.privateState, !raw.isEmpty {
                    AdminJSONDisclosure(title: "内部状态", json: AdminJSON.pretty(raw))
                }
            }

            Section {
                Button("删除任务", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .disabled(deleting)
            }
        }
        .confirmationDialog("删除此任务记录？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除任务", role: .destructive, action: delete)
        }
    }
}
