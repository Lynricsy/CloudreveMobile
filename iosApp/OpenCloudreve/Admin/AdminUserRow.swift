import SwiftUI
import OpenCloudreveCore

/// 用户一行：昵称、状态、邮箱、用户组与已用空间
struct AdminUserRow: View {
    let user: AdminUser

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(verbatim: user.nick.isEmpty ? user.email : user.nick)
                    .font(.headline)
                if user.status != "active" {
                    AdminBadge(text: AdminLabels.userStatus(user.status), color: .orange)
                }
                if user.twoFaEnabled {
                    Image(systemName: "lock.shield")
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(Text("已开启两步验证"))
                }
            }
            Text(verbatim: user.email)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(verbatim: "\(user.edges.group?.name ?? "#\(user.groupUsers)") · \(SettingsFormat.bytes(user.storage))")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("admin.userRow")
    }
}
