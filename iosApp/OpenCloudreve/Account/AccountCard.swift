import SwiftUI

/// 当前账户卡片：头像、昵称、邮箱与站点。辅助功能字号下头像与文字上下排列、文字不截断。
struct AccountCard: View {
    let nickname: String
    let email: String
    let host: String
    let avatarUrl: String
    @ScaledMetric(relativeTo: .title3) private var avatarSize: CGFloat = 64
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let large = dynamicTypeSize.isAccessibilitySize
        let layout = large
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(spacing: 16))
        layout {
            AccountAvatar(url: avatarUrl, name: nickname, size: avatarSize)
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
            .lineLimit(large ? nil : 1)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("me.accountCard")
    }
}
