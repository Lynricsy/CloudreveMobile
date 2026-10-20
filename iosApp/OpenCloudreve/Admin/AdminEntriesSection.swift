import SwiftUI

/// 各管理页面入口
struct AdminEntriesSection: View {
    let accountId: String

    var body: some View {
        Section("管理") {
            NavigationLink {
                AdminUsersScreen(accountId: accountId)
            } label: {
                Label("用户", systemImage: "person.2")
            }
            .accessibilityIdentifier("admin.users")
            NavigationLink {
                AdminSharesScreen(accountId: accountId)
            } label: {
                Label("分享", systemImage: "square.and.arrow.up")
            }
            .accessibilityIdentifier("admin.shares")
            NavigationLink {
                AdminFilesScreen(accountId: accountId)
            } label: {
                Label("文件", systemImage: "doc.on.doc")
            }
            .accessibilityIdentifier("admin.files")
            NavigationLink {
                AdminQueueScreen(accountId: accountId)
            } label: {
                Label("任务队列", systemImage: "list.bullet.rectangle")
            }
            .accessibilityIdentifier("admin.queue")
            NavigationLink {
                AdminNodesScreen(accountId: accountId)
            } label: {
                Label("节点", systemImage: "server.rack")
            }
            .accessibilityIdentifier("admin.nodes")
        }
    }
}
