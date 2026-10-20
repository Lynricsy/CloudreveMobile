import SwiftUI
import OpenCloudreveCore

/// 分享头部：分享者、访问/下载次数、有效期
struct ShareVisitHeader: View {
    let share: Share
    /// 链接站点与当前账户不同
    let siteMismatch: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let owner = share.owner {
                Label {
                    Text("\(owner.nickname) 分享", comment: "分享头部，参数为分享者昵称")
                } icon: {
                    Image(systemName: "person.crop.circle")
                }
                .font(.headline)
            }
            HStack(spacing: 16) {
                Label("\(Int(share.visited)) 次浏览", systemImage: "eye")
                Label("\(Int(share.downloaded)) 次下载", systemImage: "arrow.down.circle")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            if let expires = FileFormat.date(share.expires) {
                Label {
                    Text("有效期至 \(expires.formatted(date: .abbreviated, time: .shortened))", comment: "分享过期时间")
                } icon: {
                    Image(systemName: "clock")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            if siteMismatch {
                Label("此链接来自其他站点，正在用当前账户的站点打开。", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
