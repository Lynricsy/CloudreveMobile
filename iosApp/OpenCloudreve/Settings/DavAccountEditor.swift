import SwiftUI
import OpenCloudreveCore

/// 新建 / 编辑 WebDAV 账号
struct DavAccountEditor: View {
    let accountId: String
    let canProxy: Bool
    let saving: Bool
    let error: String?
    let save: (DavAccountDraft) -> Void
    let cancel: () -> Void
    private let isNew: Bool
    @State private var name: String
    @State private var uri: String
    @State private var readonly: Bool
    @State private var proxy: Bool
    @State private var disableSysFiles: Bool
    @State private var pickingFolder = false

    init(
        accountId: String,
        initial: DavAccountDraft?,
        canProxy: Bool,
        saving: Bool,
        error: String?,
        save: @escaping (DavAccountDraft) -> Void,
        cancel: @escaping () -> Void
    ) {
        self.accountId = accountId
        self.canProxy = canProxy
        self.saving = saving
        self.error = error
        self.save = save
        self.cancel = cancel
        isNew = initial == nil
        _name = State(initialValue: initial?.name ?? "")
        _uri = State(initialValue: initial?.uri ?? "cloudreve://my")
        _readonly = State(initialValue: initial?.readonly ?? false)
        _proxy = State(initialValue: initial?.proxy ?? false)
        _disableSysFiles = State(initialValue: initial?.disableSysFiles ?? false)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称", text: $name)
                        .accessibilityIdentifier("webdav.name")
                } footer: {
                    Text("用于区分不同设备或应用，例如“电脑”“播放器”。")
                }

                Section {
                    Button {
                        pickingFolder = true
                    } label: {
                        LabeledContent {
                            Text(verbatim: DavUriText.display(uri))
                                .lineLimit(1)
                                .truncationMode(.head)
                        } label: {
                            Label("根目录", systemImage: "folder")
                                .foregroundStyle(.primary)
                        }
                    }
                    .accessibilityIdentifier("webdav.root")
                } footer: {
                    Text("通过此账号只能访问根目录及其子目录。")
                }

                Section {
                    Toggle("只读", isOn: $readonly)
                    if canProxy {
                        Toggle("反向代理", isOn: $proxy)
                    }
                    Toggle("阻止上传系统文件", isOn: $disableSysFiles)
                } footer: {
                    Text("反向代理：下载经由服务器中转。阻止上传系统文件：忽略 .DS_Store、Thumbs.db 等系统生成的文件。")
                }

                if let error {
                    Section {
                        ErrorRow(message: error)
                    }
                }
            }
            .navigationTitle(isNew ? Text("新建 WebDAV 账号") : Text("编辑 WebDAV 账号"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", systemImage: "xmark", action: cancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button("保存", systemImage: "checkmark") {
                            save(DavAccountDraft(name: name, uri: uri, readonly: readonly, proxy: proxy && canProxy, disableSysFiles: disableSysFiles))
                        }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("webdav.save")
                    }
                }
            }
            .sheet(isPresented: $pickingFolder) {
                FolderPickerSheet(accountId: accountId, initialUri: uri, title: "选择根目录", onPick: { uri = $0 })
            }
        }
        .interactiveDismissDisabled(saving)
    }
}
