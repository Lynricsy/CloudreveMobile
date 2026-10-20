import SwiftUI
import OpenCloudreveCore

struct ArchiveExtractForm: View {
    @Bindable var host: ArchiveTaskHost
    let accountId: String
    /// 预览时选的文件名编码
    let encoding: String?

    @Environment(\.dismiss) private var dismiss
    @State private var isPickerPresented = false
    @State private var isCreatedPresented = false
    @State private var errorMessage: String?

    var body: some View {
        let state = host.state
        Form {
            Section("解压到") {
                Button {
                    isPickerPresented = true
                } label: {
                    LabeledContent("目标文件夹") {
                        Text(verbatim: FileFormat.folderTitle(state.dstUri))
                    }
                }
                .accessibilityIdentifier("extract.destination")
            }
            if !state.fileMask.isEmpty {
                Section {
                    Text("只解压所选的 \(state.fileMask.count) 项")
                }
            }
            if state.showPassword {
                Section("密码") {
                    SecureField("压缩包密码（没有可留空）", text: $host.password)
                }
            }
            if state.showEncoding {
                Section {
                    LabeledContent("文件名编码") {
                        Text(verbatim: state.encoding.isEmpty ? String(localized: "自动") : state.encoding)
                    }
                }
            }
            if state.canSelectNode && !state.nodes.isEmpty {
                Section("处理节点") {
                    ArchiveNodeMenu(
                        nodes: state.nodes.map { ArchiveNodeOption(id: $0.id, name: $0.name) },
                        selectedId: state.selectedNodeId
                    ) { id in
                        host.send(ArchiveTaskIntentSelectNode(nodeId: id))
                    }
                }
            }
            if !state.permitted {
                ErrorRow(message: String(localized: "没有创建解压任务的权限"))
            }
            if let loadError = state.loadError {
                ErrorRow(message: ErrorText.message(loadError))
            }
            if let errorMessage {
                ErrorRow(message: errorMessage)
            }
            Section {
                PrimaryActionButton(title: "开始解压", busy: state.submitting) {
                    errorMessage = nil
                    host.send(ArchiveTaskIntentSubmit.shared)
                }
                .disabled(!state.canSubmit)
                .accessibilityIdentifier("extract.submit")
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("解压")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", systemImage: "xmark") { dismiss() }
            }
        }
        .sheet(isPresented: $isPickerPresented) {
            FolderPickerSheet(accountId: accountId, initialUri: state.dstUri, title: "选择目标文件夹") { uri in
                host.send(ArchiveTaskIntentSetDestination(uri: uri))
            }
        }
        .alert("已创建解压任务", isPresented: $isCreatedPresented) {
            Button("好") { dismiss() }
        } message: {
            Text("可以在“任务”中查看进度。")
        }
        .onAppear {
            if let encoding, host.state.encoding != encoding {
                host.send(ArchiveTaskIntentSetEncoding(encoding: encoding))
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
