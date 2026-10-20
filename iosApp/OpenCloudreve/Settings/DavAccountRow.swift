import SwiftUI
import OpenCloudreveCore

/// WebDAV 账号一行：名称、根目录、选项；点按编辑，长按拷贝连接信息
struct DavAccountRow: View {
    let item: DavAccountItem
    let deleting: Bool
    let edit: () -> Void
    let delete: () -> Void

    var body: some View {
        Button(action: edit) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: item.account.name)
                        .foregroundStyle(.primary)
                    Label {
                        Text(verbatim: DavUriText.display(item.account.uri))
                    } icon: {
                        Image(systemName: "folder")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        if item.readonly {
                            Text("只读")
                        }
                        if item.proxy {
                            Text("反向代理")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                if deleting {
                    ProgressView()
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(deleting)
        .swipeActions {
            Button("删除", systemImage: "trash", role: .destructive, action: delete)
        }
        .contextMenu {
            Button("拷贝地址", systemImage: "link") { UIPasteboard.general.string = item.credentials.url }
            Button("拷贝用户名", systemImage: "person") { UIPasteboard.general.string = item.credentials.username }
            Button("拷贝密码", systemImage: "key") { UIPasteboard.general.string = item.credentials.password }
            Button("拷贝全部连接信息", systemImage: "doc.on.doc") {
                let c = item.credentials
                UIPasteboard.general.string = "\(c.url)\n\(c.username)\n\(c.password)"
            }
            Divider()
            Button("编辑", systemImage: "pencil", action: edit)
            Button("删除", systemImage: "trash", role: .destructive, action: delete)
        }
        .accessibilityIdentifier("webdav.account")
    }
}
