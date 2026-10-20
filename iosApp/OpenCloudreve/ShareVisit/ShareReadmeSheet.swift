import SwiftUI
import MarkdownUI

/// 分享目录的 README（Markdown 渲染）
struct ShareReadmeSheet: View {
    let title: String
    let content: String?
    let loading: Bool

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                if let content {
                    Markdown(content)
                        .textSelection(.enabled)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if loading {
                    ProgressView()
                        .padding()
                } else {
                    ContentUnavailableView("无法显示说明文件", systemImage: "doc.text", description: Text("文件过大或下载失败。"))
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}
