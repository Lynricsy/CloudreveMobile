import SwiftUI

/// 当前账户卡片：头像、昵称、邮箱与站点
struct AccountCard: View {
    let nickname: String
    let email: String
    let host: String
    let avatarUrl: String

    var body: some View {
        HStack(spacing: 16) {
            AccountAvatar(url: avatarUrl, name: nickname, size: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: nickname)
                    .font(.title3.bold())
                Text(verbatim: email)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Label {
                    Text(verbatim: host)
                } icon: {
                    Image(systemName: "server.rack")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .lineLimit(1)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("me.accountCard")
    }
}
