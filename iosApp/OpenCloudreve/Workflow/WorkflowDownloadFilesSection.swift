import SwiftUI
import OpenCloudreveCore

/// 离线下载的文件列表：可选择时用开关勾选，“应用选择”发 `SelectFiles`。
/// 本地勾选在服务端选择变化（轮询返回新状态）时重置。
struct WorkflowDownloadFilesSection: View {
    let taskId: String
    let files: [DownloadTaskFile]
    let canSelect: Bool
    let busy: Bool
    let onConfirm: ([DownloadFileSelection]) -> Void

    /// 文件索引 → 是否下载
    @State private var selection: [Int32: Bool] = [:]

    var body: some View {
        let server = serverSelection
        Section {
            ForEach(files, id: \.index) { file in
                if canSelect {
                    Toggle(isOn: binding(file.index)) {
                        label(file)
                    }
                    .disabled(busy)
                } else {
                    label(file)
                }
            }
            if canSelect {
                Button("应用选择") {
                    onConfirm(files.map { DownloadFileSelection(index: $0.index, download: selection[$0.index] ?? $0.selected) })
                }
                .disabled(busy || selection == server || !selection.values.contains(true))
                .accessibilityIdentifier("workflow.detail.files.confirm")
            }
        } header: {
            Text("文件")
        } footer: {
            if canSelect {
                Text("选择要下载的文件，未选中的文件不会下载。")
            }
        }
        .onChange(of: server, initial: true) { _, newValue in
            selection = newValue
        }
        .onChange(of: taskId) { _, _ in
            selection = server
        }
    }

    /// 服务端当前的选择
    private var serverSelection: [Int32: Bool] {
        Dictionary(files.map { ($0.index, $0.selected) }, uniquingKeysWith: { first, _ in first })
    }

    private func binding(_ index: Int32) -> Binding<Bool> {
        Binding(
            get: { selection[index] ?? false },
            set: { selection[index] = $0 }
        )
    }

    private func label(_ file: DownloadTaskFile) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: file.name)
                .lineLimit(2)
                .truncationMode(.middle)
            Text(verbatim: "\(FileFormat.bytes(file.size)) · \(Int((file.progress * 100).rounded()))%")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }
}
