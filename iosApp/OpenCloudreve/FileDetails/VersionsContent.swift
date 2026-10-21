import SwiftUI
import OpenCloudreveCore

/// 版本列表内容：右滑设为当前 / 预览，左滑删除（需确认）；长按菜单提供同样的操作
struct VersionsContent: View {
    let accountId: String
    let host: VersionsHost
    /// 信息加载前用于预览的文件
    let fallback: FileItem

    @State private var deleting: VersionRow?
    @State private var previewing: VersionPreviewTarget?
    @State private var failure: String?

    var body: some View {
        let state = host.state
        List {
            if let error = state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(VersionsIntentRefresh.shared) }
                }
            } else if state.versions.isEmpty {
                Section {
                    if state.loading {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Text("没有历史版本")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if !state.versions.isEmpty {
                Section {
                    ForEach(state.versions, id: \.id) { row in
                        VersionRowView(row: row, busy: state.mutatingId == row.id)
                            .swipeActions(edge: .leading) {
                                if row.canModify {
                                    setCurrentButton(row)
                                        .tint(.accentColor)
                                }
                                previewButton(row, file: state.file ?? fallback)
                                    .tint(.indigo)
                            }
                            .swipeActions(edge: .trailing) {
                                if row.canModify {
                                    deleteButton(row)
                                        .tint(.red)
                                }
                            }
                            .contextMenu {
                                previewButton(row, file: state.file ?? fallback)
                                if row.canModify {
                                    setCurrentButton(row)
                                    deleteButton(row)
                                }
                            }
                    }
                } footer: {
                    if state.canManage {
                        Text("右滑可设为当前版本或预览，左滑删除旧版本。")
                    }
                }
            }
        }
        .refreshable { host.send(VersionsIntentRefresh.shared) }
        .confirmationDialog("删除此版本？", isPresented: isDeletePresented, titleVisibility: .visible, presenting: deleting) { row in
            Button("删除", role: .destructive) {
                host.send(VersionsIntentDelete(entityId: row.id))
            }
            Button("取消", role: .cancel) {}
        } message: { _ in
            Text("删除后无法恢复。")
        }
        .fullScreenCover(item: $previewing) { target in
            ViewerScreen(accountId: accountId, file: target.file, version: target.entityId)
        }
        .errorMessageAlert(message: $failure)
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .updated:
                    break
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
    }

    private var isDeletePresented: Binding<Bool> {
        Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
    }

    private func setCurrentButton(_ row: VersionRow) -> some View {
        Button("设为当前版本", systemImage: "checkmark.circle") {
            host.send(VersionsIntentSetCurrent(entityId: row.id))
        }
        .accessibilityIdentifier("version.setCurrent")
    }

    private func deleteButton(_ row: VersionRow) -> some View {
        Button("删除", systemImage: "trash") {
            deleting = row
        }
        .accessibilityIdentifier("version.delete")
    }

    private func previewButton(_ row: VersionRow, file: FileItem) -> some View {
        Button("预览此版本", systemImage: "eye") {
            previewing = VersionPreviewTarget(file: file, entityId: row.id)
        }
        .accessibilityIdentifier("version.preview")
    }
}
