import SwiftUI
import UIKit

/// 一条链接：可选标题、链接正文（可选择）、附注，以及“复制”按钮
struct LinkCopyRow: View {
    /// 用户数据（文件名、分享名），原样显示
    var title: String?
    let link: String
    /// 复制到剪贴板的文本（缺省为链接本身）
    var copyText: String?
    /// 附注（下载次数、有效期等）
    var note: String?
    /// 链接正文的无障碍标识
    var linkIdentifier: String?

    @State private var copied = false

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                if let title {
                    Text(verbatim: title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                }
                Text(verbatim: link)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .accessibilityIdentifier(linkIdentifier ?? "")
                if let note {
                    Text(verbatim: note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = copyText ?? link
                copied = true
            } label: {
                Label(copyTitle, systemImage: copied ? "checkmark" : "doc.on.doc")
                    .labelStyle(.iconOnly)
            }
            .buttonStyle(.borderless)
            .sensoryFeedback(.success, trigger: copied)
        }
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(2))
            copied = false
        }
    }

    private var copyTitle: LocalizedStringKey {
        copied ? "已复制" : "复制"
    }
}