import SwiftUI

/// 服务端版本与站点地址
struct AdminSiteSection: View {
    let version: String
    let commit: String
    let siteUrls: [String]

    var body: some View {
        Section("站点") {
            LabeledContent("服务端版本") {
                Text(verbatim: commit.isEmpty ? version : "\(version) (\(commit.prefix(7)))")
            }
            ForEach(siteUrls, id: \.self) { url in
                LabeledContent("站点地址") {
                    Text(verbatim: url)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                }
            }
        }
    }
}
