import SwiftUI

/// 用户头像；服务端无头像时返回 404，显示昵称首字母
struct AccountAvatar: View {
    let url: String
    let name: String
    let size: CGFloat

    var body: some View {
        AsyncImage(url: URL(string: url)) { phase in
            if let image = phase.image {
                image
                    .resizable()
                    .scaledToFill()
            } else {
                Text(verbatim: initial)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.tint)
            }
        }
        .frame(width: size, height: size)
        .clipShape(.circle)
        .accessibilityHidden(true)
    }

    private var initial: String {
        name.first.map { String($0).uppercased() } ?? "?"
    }
}
