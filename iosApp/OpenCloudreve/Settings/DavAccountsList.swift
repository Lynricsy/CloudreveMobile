import SwiftUI
import OpenCloudreveCore

struct DavAccountsList: View {
    let accountId: String
    let host: DavAccountsHost
    @State private var editor: DavEditorTarget?
    /// 编辑弹层内显示的错误（弹层盖住本页，提示框无法从本页弹出）
    @State private var editorError: String?
    @State private var failure: String?
    @State private var pendingDelete: DavAccountItem?

    var body: some View {
        List {
            if host.state.loading && host.state.items.isEmpty {
                Section {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            }
            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(DavAccountsIntentRefresh.shared) }
                }
            }

            if !host.state.davUrl.isEmpty {
                DavConnectionSection(url: host.state.davUrl, username: host.state.username)
            }

            if !host.state.loading && host.state.error == nil && !host.state.webdavEnabled {
                Section {
                    Label("当前用户组未启用 WebDAV", systemImage: "nosign")
                        .foregroundStyle(.secondary)
                }
            } else if !host.state.items.isEmpty || host.state.webdavEnabled {
                Section {
                    if host.state.items.isEmpty && !host.state.loading {
                        Text("还没有 WebDAV 账号")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(host.state.items, id: \.id) { item in
                        DavAccountRow(
                            item: item,
                            deleting: host.state.deletingId == item.id,
                            edit: { open(item) },
                            delete: { pendingDelete = item }
                        )
                    }
                } header: {
                    Text("账号")
                } footer: {
                    Text("每个账号有独立的密码和根目录；长按账号可拷贝连接信息。")
                }
            }
        }
        .refreshable { host.send(DavAccountsIntentRefresh.shared) }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("新建账号", systemImage: "plus") { open(nil) }
                    .disabled(!host.state.canCreate)
                    .accessibilityIdentifier("webdav.add")
            }
        }
        .sheet(item: $editor) { target in
            DavAccountEditor(
                accountId: accountId,
                initial: target.item.map { DavAccountDraft.companion.from(account: $0.account) },
                canProxy: host.state.canProxy,
                saving: host.state.saving,
                error: editorError,
                save: { draft in
                    editorError = nil
                    if let item = target.item {
                        host.send(DavAccountsIntentUpdate(id: item.id, draft: draft))
                    } else {
                        host.send(DavAccountsIntentCreate(draft: draft))
                    }
                },
                cancel: { editor = nil }
            )
        }
        .confirmationDialog("删除此 WebDAV 账号？", isPresented: isConfirmingDelete, titleVisibility: .visible, presenting: pendingDelete) { item in
            Button("删除", role: .destructive) { host.send(DavAccountsIntentDelete(id: item.id)) }
        } message: { _ in
            Text("使用此账号连接的设备将无法再访问。")
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .saved:
                    editor = nil
                case .deleted:
                    break
                case let .failed(failed):
                    let message = ErrorText.message(failed.error)
                    if editor != nil {
                        editorError = message
                    } else {
                        failure = message
                    }
                }
            }
        }
        .errorMessageAlert(message: $failure)
    }

    private func open(_ item: DavAccountItem?) {
        editorError = nil
        editor = DavEditorTarget(item: item)
    }

    private var isConfirmingDelete: Binding<Bool> {
        Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
    }
}
