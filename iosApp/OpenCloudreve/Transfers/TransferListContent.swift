import SwiftUI
import OpenCloudreveCore

/// 传输列表：上传、下载分组；同名冲突可改名或覆盖后重试；右上角清除已完成
struct TransferListContent: View {
    let host: TransferListHost

    @State private var renaming: RenameRequest?
    @State private var newName = ""
    @State private var errorMessage: String?

    /// 冲突改名请求
    private struct RenameRequest: Identifiable {
        let id: String
        let name: String
    }

    var body: some View {
        let state = host.state
        List {
            if !state.uploads.isEmpty {
                Section("上传") {
                    rows(state.uploads, busyIds: state.busyIds)
                }
            }
            if !state.downloads.isEmpty {
                Section {
                    rows(state.downloads, busyIds: state.busyIds)
                } header: {
                    Text("下载")
                } footer: {
                    Text("下载的文件保存在“文件”App 的“我的 iPhone › OpenCloudreve › Downloads”中。")
                }
            }
        }
        .overlay {
            if !state.loaded {
                ProgressView()
            } else if state.isEmpty {
                ContentUnavailableView {
                    Label("没有传输任务", systemImage: "arrow.up.arrow.down")
                } description: {
                    Text("上传和下载的文件会显示在这里。")
                }
            }
        }
        .toolbar {
            if state.hasFinished {
                ToolbarItem(placement: .primaryAction) {
                    Button("清除已完成", systemImage: "checkmark.circle.badge.xmark") {
                        host.send(TransferListIntentClearFinished.shared)
                    }
                    .accessibilityIdentifier("transfers.clearFinished")
                }
            }
        }
        .alert("重命名后上传", isPresented: renameBinding, presenting: renaming) { request in
            TextField("新名称", text: $newName)
            Button("取消", role: .cancel) {}
            Button("上传") {
                host.send(TransferListIntentRetryWithRename(id: request.id, newName: newName))
            }
        } message: { _ in
            Text("目标位置已有同名文件，换一个名称再上传。")
        }
        .alert("操作失败", isPresented: errorBinding) {
            Button("好", role: .cancel) {}
        } message: {
            Text(verbatim: errorMessage ?? "")
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .failed(failure): errorMessage = ErrorText.message(failure.error)
                }
            }
        }
    }

    private func rows(_ rows: [TransferRow], busyIds: Set<String>) -> some View {
        ForEach(rows, id: \.id) { row in
            TransferRowView(
                row: row,
                busy: busyIds.contains(row.id),
                send: { host.send($0) },
                rename: {
                    newName = row.item.displayName
                    renaming = RenameRequest(id: row.id, name: row.item.displayName)
                }
            )
        }
    }

    private var renameBinding: Binding<Bool> {
        Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })
    }

    private var errorBinding: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }
}
