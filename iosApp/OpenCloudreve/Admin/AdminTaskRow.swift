import SwiftUI
import OpenCloudreveCore

/// 任务一行：类型、状态、所属用户、创建时间
struct AdminTaskRow: View {
    let task: AdminTask

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(verbatim: AdminLabels.taskType(task.type))
                    .font(.headline)
                AdminBadge(text: AdminLabels.taskStatus(task.status), color: statusColor)
            }
            Text(verbatim: details)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let error = task.publicState?.error, !error.isEmpty {
                Text(verbatim: error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var statusColor: Color {
        switch task.status {
        case "error": .red
        case "completed": .green
        case "processing", "queued": .blue
        default: .secondary
        }
    }

    private var details: String {
        var parts = ["#\(task.id)"]
        if let user = task.edges.user {
            parts.append(user.nick.isEmpty ? user.email : user.nick)
        }
        if let created = SettingsFormat.dateTime(task.createdAt) {
            parts.append(created)
        }
        return parts.joined(separator: " · ")
    }
}
