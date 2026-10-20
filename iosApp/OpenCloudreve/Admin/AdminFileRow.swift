import SwiftUI
import OpenCloudreveCore

/// 文件一行：名称、大小、所有者、更新时间
struct AdminFileRow: View {
    let file: AdminFile

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: file.name)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(verbatim: details)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var details: String {
        var parts = [SettingsFormat.bytes(file.size)]
        if let owner = file.edges.owner {
            parts.append(owner.nick.isEmpty ? owner.email : owner.nick)
        }
        if let updated = SettingsFormat.dateTime(file.updatedAt) {
            parts.append(updated)
        }
        return parts.joined(separator: " · ")
    }
}
