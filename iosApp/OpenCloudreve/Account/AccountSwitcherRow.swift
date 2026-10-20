import SwiftUI

/// 账户切换器中的一行：点按切换，左滑或长按菜单登出
struct AccountSwitcherRow: View {
    let nickname: String
    let email: String
    let host: String
    let avatarUrl: String
    let isActive: Bool
    let sessionExpired: Bool
    let signingOut: Bool
    let select: () -> Void
    let signOut: () -> Void

    var body: some View {
        Button(action: select) {
            HStack(spacing: 12) {
                AccountAvatar(url: avatarUrl, name: nickname, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: nickname)
                        .foregroundStyle(.primary)
                    Text(verbatim: "\(email) · \(host)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if sessionExpired {
                        Label("需要重新登录", systemImage: "exclamationmark.circle")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                }
                .lineLimit(1)
                Spacer()
                if signingOut {
                    ProgressView()
                } else if isActive {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                        .accessibilityLabel(Text("当前账户"))
                }
            }
        }
        .disabled(signingOut)
        .swipeActions {
            Button("退出登录", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive, action: signOut)
        }
        .contextMenu {
            Button("退出登录", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive, action: signOut)
        }
        .accessibilityIdentifier("switcher.account")
    }
}
