import SwiftUI
import OpenCloudreveCore

struct FolderPickerContent: View {
    let host: FolderPickerHost
    let title: LocalizedStringKey
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var creatingFolder = false
    @State private var newFolderName = ""

    var body: some View {
        let state = host.state
        List {
            if state.excluded {
                Section {
                    Label("不能选择被移动的文件夹本身或其子文件夹", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
            }
            if let error = state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(FolderPickerIntentRefresh.shared) }
                }
            }
            Section {
                if state.loading && state.folders.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else if state.folders.isEmpty && state.error == nil {
                    Text("没有子文件夹")
                        .foregroundStyle(.secondary)
                }
                ForEach(state.folders, id: \.path) { folder in
                    Button {
                        host.send(FolderPickerIntentOpen(uri: folder.path))
                    } label: {
                        HStack {
                            FileTypeIconView(file: folder, size: 28)
                            Text(verbatim: folder.name)
                                .foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
                if state.hasMore {
                    LoadMoreRow(loading: state.loadingMore) {
                        host.send(FolderPickerIntentLoadMore.shared)
                    }
                    .id(state.folders.count)
                }
            } header: {
                Text(verbatim: path(state.crumbs))
            }
        }
        .navigationTitle(title)
        .navigationSubtitle(state.crumbs.last.map { FileFormat.folderTitle($0.uri) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", role: .cancel) { dismiss() }
            }
            if state.canGoUp {
                ToolbarItem(placement: .topBarLeading) {
                    Button("上一级", systemImage: "chevron.up") {
                        host.send(FolderPickerIntentUp.shared)
                    }
                }
            }
            ToolbarItem(placement: .bottomBar) {
                Button("新建文件夹", systemImage: "folder.badge.plus") {
                    newFolderName = String(localized: "新建文件夹")
                    creatingFolder = true
                }
                .disabled(!state.canCreateFolder || state.creating)
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("选择此文件夹") {
                    onPick(state.uri)
                    dismiss()
                }
                .buttonStyle(.glassProminent)
                .disabled(!state.canSelect)
                .accessibilityIdentifier("picker.confirm")
            }
        }
        .alert("新建文件夹", isPresented: $creatingFolder) {
            TextField("名称", text: $newFolderName)
            Button("取消", role: .cancel) {}
            Button("创建") {
                host.send(FolderPickerIntentCreateFolder(name: newFolderName))
            }
        }
    }

    /// 面包屑路径：我的文件 / a / b
    private func path(_ crumbs: [PathCrumb]) -> String {
        crumbs.enumerated()
            .map { index, crumb in index == 0 ? FileFormat.folderTitle(crumb.uri) : crumb.name }
            .joined(separator: " / ")
    }
}
