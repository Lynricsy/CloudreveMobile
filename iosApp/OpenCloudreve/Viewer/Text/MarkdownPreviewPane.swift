import MarkdownUI
import SwiftUI

/// Markdown 渲染预览（MarkdownUI，GitHub 风格）
struct MarkdownPreviewPane: View {
    let text: String

    var body: some View {
        ScrollView {
            Markdown(text)
                .markdownTheme(.gitHub)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .accessibilityIdentifier("text.preview")
    }
}
