import SwiftUI
import OpenCloudreveCore

/// 离线下载表单：链接或种子文件、保存目录、处理节点；40081 部分失败时列出失败项（输入框只保留失败的链接）
struct RemoteDownloadForm: View {
    @Bindable var host: RemoteDownloadCreateHost
    let accountId: String
    let onCreated: @MainActor () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isPickerPresented = false
    @State private var isCreatedPresented = false
    @State private var createdCount = 0
    @State private var errorMessage: String?

    var body: some View {
        let state = host.state
        Form {
            if let torrentName = state.torrentName, state.useTorrent {
                Section("种子文件") {
                    HStack {
                        Label {
                            Text(verbatim: torrentName)
                                .lineLimit(2)
                                .truncationMode(.middle)
                        } icon: {
                            Image(systemName: "doc")
                        }
                        Spacer()
                        Button("改用下载链接", systemImage: "xmark.circle.fill") {
                            host.send(RemoteDownloadCreateIntentSetTorrent(uri: nil))
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                    }
                }
            } else {
                Section {
                    TextField("每行一个 HTTP(S) 或磁力链接", text: $host.urlsText, axis: .vertical)
                        .lineLimit(3...8)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("remoteDownload.urls")
                } header: {
                    Text("下载链接")
                } footer: {
                    if state.sources.count > 1 {
                        Text("共 \(state.sources.count) 个链接", comment: "离线下载输入的链接数")
                    }
                }
            }

            Section("保存到") {
                WorkflowDestinationButton(title: "目标文件夹", uri: state.dstUri) {
                    isPickerPresented = true
                }
                .accessibilityIdentifier("remoteDownload.destination")
            }

            if state.canSelectNode {
                Section("处理节点") {
                    if state.loadingNodes {
                        ProgressView()
                    } else {
                        ArchiveNodeMenu(
                            nodes: state.nodes.map { ArchiveNodeOption(id: $0.id, name: $0.name.isEmpty ? $0.id : $0.name) },
                            selectedId: state.selectedNodeId
                        ) { id in
                            host.send(RemoteDownloadCreateIntentSelectNode(nodeId: id))
                        }
                    }
                }
            }

            if !state.permitted {
                ErrorRow(message: String(localized: "当前用户组无权创建离线下载"))
            }
            if !state.failures.isEmpty {
                Section("以下链接创建失败") {
                    ForEach(state.failures, id: \.uri) { failure in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verbatim: failure.uri)
                                .font(.footnote)
                                .lineLimit(2)
                                .truncationMode(.middle)
                            ErrorRow(message: ErrorText.message(failure.error))
                                .font(.footnote)
                        }
                    }
                }
            }
            if let errorMessage {
                ErrorRow(message: errorMessage)
            }

            Section {
                PrimaryActionButton(title: "开始下载", busy: state.submitting) {
                    errorMessage = nil
                    host.send(RemoteDownloadCreateIntentSubmit.shared)
                }
                .disabled(!state.canSubmit)
                .accessibilityIdentifier("remoteDownload.submit")
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("离线下载")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", systemImage: "xmark") { dismiss() }
            }
        }
        .sheet(isPresented: $isPickerPresented) {
            FolderPickerSheet(accountId: accountId, initialUri: state.dstUri, title: "选择保存位置") { uri in
                host.send(RemoteDownloadCreateIntentSetDestination(uri: uri))
            }
        }
        .workflowCreatedAlert(
            "已创建离线下载",
            isPresented: $isCreatedPresented,
            message: String(localized: "已创建 \(createdCount) 个任务，可以在“任务”中查看进度。", comment: "离线下载创建成功，参数为任务数")
        ) {
            onCreated()
            dismiss()
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .created(created):
                    if created.failedCount == 0 {
                        createdCount = created.tasks.count
                        isCreatedPresented = true
                    } else {
                        // 部分失败：成功的已创建，失败项留在表单里可修改后重试
                        errorMessage = String(
                            localized: "\(Int(created.failedCount)) 个链接创建失败，其余已创建。",
                            comment: "离线下载部分失败，参数为失败数"
                        )
                        onCreated()
                    }
                case let .failed(failed):
                    errorMessage = ErrorText.message(failed.error)
                }
            }
        }
    }
}
