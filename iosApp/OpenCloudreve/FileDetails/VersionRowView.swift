import SwiftUI
import OpenCloudreveCore

/// 版本列表一行：时间、大小、存储策略、上传者；当前版本带标记，修改中显示进度
struct VersionRowView: View {
    let row: VersionRow
    let busy: Bool

    var body: some View {
        HStack(spacing: 12) {
            // 行内容合并为一个无障碍元素（测试按 `version.row` 计数）；当前标记单独成元素
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: FileFormat.dateTime(row.entity.createdAt) ?? "—")
                Text(verbatim: details)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("version.row")
            if busy {
                ProgressView()
            }
            if row.isCurrent {
                Text("当前版本")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.tint.opacity(0.15), in: .capsule)
                    .accessibilityIdentifier("version.current")
            }
        }
    }

    /// 大小 · 存储策略 · 上传者
    private var details: String {
        let entity = row.entity
        var parts = [FileFormat.bytes(entity.size)]
        if let policy = entity.storagePolicy?.name, !policy.isEmpty {
            parts.append(policy)
        }
        if let creator = entity.createdBy?.nickname, !creator.isEmpty {
            parts.append(String(localized: "上传者：\(creator)", comment: "版本列表中的上传者，参数为昵称"))
        }
        return parts.joined(separator: " · ")
    }
}
