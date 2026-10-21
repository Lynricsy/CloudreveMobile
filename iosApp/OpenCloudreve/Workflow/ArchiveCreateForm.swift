import SwiftUI
import OpenCloudreveCore

/// 压缩表单：压缩包名、所在目录、处理节点；创建成功提示后关闭
struct ArchiveCreateForm: View {
    @Bindable var host: ArchiveTaskHost
    let accountId: String
    let initialName: String?
    let onCreated: @MainActor () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isPickerPresented = false
    @State private var isCreatedPresented = false
    @State private var errorMessage: String?

    var body: some View {
        let state = host.state
        Form {
            Section {
                TextField("压缩包名称", text: $host.archiveName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("compress.name")
            } header: {
                Text("压缩包名称")
            } footer: {
                if state.srcUris.count > 1 {
                    Text("将压缩“\(state.sourceName)”等 \(state.srcUris.count) 项", comment: "压缩多个文件，参数为第一个文件名与总数")
                } else {
                    Text("将压缩“\(state.sourceName)”", comment: "压缩单个文件，参数为文件名")
                }
            }

            Section("保存到") {
                WorkflowDestinationButton(title: "所在文件夹", uri: state.dstUri) {
                    isPickerPresented = true
                }
                .accessibilityIdentifier("compress.destination")
            }

            if state.canSelectNode && !state.nodes.isEmpty {
                Section("处理节点") {
                    ArchiveNodeMenu(
                        nodes: state.nodes.map { ArchiveNodeOption(id: $0.id, name: $0.name.isEmpty ? $0.id : $0.name) },
                        selectedId: state.selectedNodeId
                    ) { id in
                        host.send(ArchiveTaskIntentSelectNode(nodeId: id))
                    }
                }
            }

            if !state.permitted {
                ErrorRow(message: String(localized: "当前用户组无权创建压缩任务"))
            }
            if let errorMessage {
                ErrorRow(message: errorMessage)
            }

            Section {
                PrimaryActionButton(title: "开始压缩", busy: state.submitting) {
                    errorMessage = nil
                    host.send(ArchiveTaskIntentSubmit.shared)
                }
                .disabled(!state.canSubmit)
                .accessibilityIdentifier("compress.submit")
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("压缩")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", systemImage: "xmark") { dismiss() }
            }
        }
        .sheet(isPresented: $isPickerPresented) {
            FolderPickerSheet(accountId: accountId, initialUri: state.dstUri, title: "选择保存位置") { uri in
                host.send(ArchiveTaskIntentSetDestination(uri: uri))
            }
        }
        .workflowCreatedAlert(
            "已创建压缩任务",
            isPresented: $isCreatedPresented,
            message: String(localized: "可以在“任务”中查看进度。")
        ) {
            onCreated()
            dismiss()
        }
        .onAppear {
            if let initialName, host.state.archiveName == ArchiveTaskState.companion.DEFAULT_ARCHIVE_NAME {
                host.archiveName = initialName
            }
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .created:
                    isCreatedPresented = true
                case let .failed(failed):
                    errorMessage = ErrorText.message(failed.error)
                }
            }
        }
    }
}
