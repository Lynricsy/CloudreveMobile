import UIKit
import SwiftUI
import OpenCloudreveCore

/// 直链生成结果：逐条复制或全部复制；失败项列出原因
struct SourceLinksSheet: View {
    let links: [SourceLink]
    let failures: [OperationFailure]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !links.isEmpty {
                    Section {
                        ForEach(links, id: \.link) { link in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: link.link)
                                    .font(.callout.monospaced())
                                    .textSelection(.enabled)
                                Button("复制", systemImage: "doc.on.doc") {
                                    UIPasteboard.general.string = link.link
                                }
                                .font(.footnote)
                            }
                        }
                    } footer: {
                        Text("直链长期有效，任何拿到链接的人都可以下载该文件。")
                    }
                }
                if !failures.isEmpty {
                    Section("未能生成") {
                        ForEach(failures, id: \.uri) { failure in
                            LabeledContent {
                                Text(ErrorText.message(failure.error))
                            } label: {
                                Text(verbatim: CrUri.companion.parseOrNull(s: failure.uri)?.name() ?? failure.uri)
                            }
                        }
                    }
                }
            }
            .navigationTitle("直链")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
                if links.count > 1 {
                    ToolbarItem(placement: .bottomBar) {
                        Button("全部复制", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = links.map(\.link).joined(separator: "\n")
                        }
                    }
                }
            }
        }
    }
}
