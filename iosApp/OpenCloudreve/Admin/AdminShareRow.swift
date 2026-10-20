import SwiftUI
import OpenCloudreveCore

/// 分享一行：文件名、所有者、浏览/下载次数、过期
struct AdminShareRow: View {
    let share: AdminShare

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(verbatim: share.edges.file?.name ?? "#\(share.id)")
                    .font(.headline)
                    .lineLimit(1)
                if let password = share.password, !password.isEmpty {
                    Image(systemName: "lock")
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(Text("有密码"))
                }
            }
            if let owner = share.edges.user {
                Text(verbatim: owner.nick.isEmpty ? owner.email : owner.nick)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text("浏览 \(Int(share.views)) · 下载 \(Int(share.downloads))")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}
