import SwiftUI

/// 登录页顶部：站点标题、地址与服务端版本
struct LoginHeader: View {
    let title: String
    let siteUrl: String
    let version: String?

    var body: some View {
        Section {
            VStack(spacing: 6) {
                Image(systemName: "cloud.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                if !title.isEmpty {
                    Text(verbatim: title)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                }
                Text(verbatim: siteUrl)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let version {
                    Text("服务端版本 \(version)", comment: "登录页显示的服务端版本号")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }
        .listRowBackground(Color.clear)
    }
}
