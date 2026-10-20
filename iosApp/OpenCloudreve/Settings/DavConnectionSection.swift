import SwiftUI

/// WebDAV 连接信息：地址与用户名（可拷贝）
struct DavConnectionSection: View {
    let url: String
    let username: String

    var body: some View {
        Section {
            CopyableRow(title: "地址", value: url)
                .accessibilityIdentifier("webdav.url")
            CopyableRow(title: "用户名", value: username)
        } header: {
            Text("连接信息")
        } footer: {
            Text("在支持 WebDAV 的应用中使用以上地址、用户名和账号密码连接。")
        }
    }
}
