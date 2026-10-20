import SwiftUI
import OpenCloudreveCore

/// 分享详情：链接、文件、所有者、计数、过期与选项；可删除
struct AdminShareDetail: View {
    let share: AdminShare
    let deleting: Bool
    let delete: () -> Void
    @State private var confirmingDelete = false

    var body: some View {
        Form {
            if let link = share.shareLink, !link.isEmpty {
                Section("链接") {
                    CopyableRow(title: "分享链接", value: link)
                    if let password = share.password, !password.isEmpty {
                        CopyableRow(title: "密码", value: password)
                    }
                }
            }
            Section("信息") {
                LabeledContent("文件") { Text(verbatim: share.edges.file?.name ?? "—") }
                if let owner = share.edges.user {
                    LabeledContent("所有者") { Text(verbatim: "\(owner.nick) (\(owner.email))") }
                }
                LabeledContent("浏览次数") { Text(Int(share.views), format: .number) }
                LabeledContent("下载次数") { Text(Int(share.downloads), format: .number) }
                if let remain = share.remainDownloads {
                    LabeledContent("剩余下载次数") { Text(Int(remain.int32Value), format: .number) }
                }
                if let created = SettingsFormat.dateTime(share.createdAt) {
                    LabeledContent("创建时间") { Text(verbatim: created) }
                }
                LabeledContent("过期时间") {
                    if let expires = SettingsFormat.dateTime(share.expires) {
                        Text(verbatim: expires)
                    } else {
                        Text("永不过期")
                    }
                }
            }
            if let props = share.props {
                Section("选项") {
                    LabeledContent("以目录视图展示") { Text(props.shareView ? "是" : "否") }
                    LabeledContent("显示 README") { Text(props.showReadMe ? "是" : "否") }
                }
            }
            Section {
                Button("删除分享", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .disabled(deleting)
            }
        }
        .confirmationDialog("删除此分享？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除分享", role: .destructive, action: delete)
        }
    }
}
