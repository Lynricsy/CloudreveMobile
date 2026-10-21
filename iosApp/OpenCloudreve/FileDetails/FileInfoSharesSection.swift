import SwiftUI
import OpenCloudreveCore

/// 信息页“分享”分组：该文件的分享链接（浏览次数、有效期），可复制
struct FileInfoSharesSection: View {
    let shares: [Share]

    var body: some View {
        if !shares.isEmpty {
            Section("分享") {
                ForEach(shares, id: \.id) { share in
                    if share.url.isEmpty {
                        LabeledContent {
                            Text(verbatim: note(share))
                        } label: {
                            Text(verbatim: share.name ?? share.id)
                        }
                    } else {
                        LinkCopyRow(title: share.name ?? share.id, link: share.url, note: note(share))
                    }
                }
            }
        }
    }

    /// 浏览次数 · 有效期
    private func note(_ share: Share) -> String {
        var parts = [String(localized: "\(Int(share.visited)) 次浏览", comment: "分享浏览次数")]
        if share.expired {
            parts.append(String(localized: "已过期"))
        } else if let expires = FileFormat.dateTime(share.expires) {
            parts.append(String(localized: "\(expires) 过期", comment: "分享到期时间，参数为日期时间"))
        }
        if share.isPrivate || share.passwordProtected {
            parts.append(String(localized: "需要密码"))
        }
        return parts.joined(separator: " · ")
    }
}
