import SwiftUI
import OpenCloudreveCore

struct AdminUsersContent: View {
    let accountId: String
    let host: AdminListHost<AdminUser>
    @State private var groups: [AdminGroup] = []
    /// 要浏览其文件的用户根目录（`cloudreve://<hashid>@my`）
    @State private var filesUri: String?

    private static let statuses = ["active", "inactive", "manual_banned", "sys_banned"]

    var body: some View {
        AdminEntityList(
            host: host,
            supportsDelete: true,
            searchFields: [
                AdminSearchField(key: AdminConditions.shared.USER_EMAIL, title: "邮箱"),
                AdminSearchField(key: AdminConditions.shared.USER_NICK, title: "昵称"),
            ],
            idOf: { $0.id },
            header: { EmptyView() },
            filters: {
                AdminConditionPicker(
                    host: host,
                    key: AdminConditions.shared.USER_STATUS,
                    title: "状态",
                    options: Self.statuses.map { AdminOption(value: $0, label: AdminLabels.userStatus($0)) }
                )
                if !groups.isEmpty {
                    AdminConditionPicker(
                        host: host,
                        key: AdminConditions.shared.USER_GROUP,
                        title: "用户组",
                        options: groups.map { AdminOption(value: String($0.id), label: $0.name) }
                    )
                }
            },
            row: { user in
                AdminUserRow(user: user)
                    .contextMenu {
                        if let viewModel = host.viewModel as? AdminUsersViewModel {
                            Button("浏览此用户的文件", systemImage: "folder") {
                                filesUri = viewModel.filesUri(user: user)
                            }
                        }
                    }
            },
            destination: { user in
                AdminUserEditScreen(accountId: accountId, userId: user.id) {
                    host.send(AdminListIntentRefresh.shared)
                }
            }
        )
        .navigationDestination(item: $filesUri) { uri in
            FileListScreen(accountId: accountId, uri: uri)
        }
        .task {
            guard let viewModel = host.viewModel as? AdminUsersViewModel else { return }
            for await value in viewModel.groups {
                groups = value
            }
        }
    }
}
