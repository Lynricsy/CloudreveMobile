import SwiftUI

/// 分享行上下文菜单项
struct ShareRowMenuItems: View {
    /// 有可用链接（列表接口返回了 `url`）
    let hasLink: Bool
    let perform: (ShareRowAction) -> Void

    var body: some View {
        Section {
            Button("复制链接", systemImage: "doc.on.doc") { perform(.copy) }
                .disabled(!hasLink)
                .accessibilityIdentifier("share.action.copy")
            Button("二维码", systemImage: "qrcode") { perform(.qr) }
                .disabled(!hasLink)
                .accessibilityIdentifier("share.action.qr")
        }
        Section {
            Button("编辑", systemImage: "pencil") { perform(.edit) }
                .accessibilityIdentifier("share.action.edit")
            Button("前往源文件", systemImage: "folder") { perform(.source) }
                .accessibilityIdentifier("share.action.source")
        }
        Section {
            Button("删除", systemImage: "trash", role: .destructive) { perform(.delete) }
                .accessibilityIdentifier("share.action.delete")
        }
    }
}
