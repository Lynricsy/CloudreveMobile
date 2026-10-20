import SwiftUI
import OpenCloudreveCore

/// 文件详细信息（`GET /file/info?extended=true&folder_summary=true`），sheet 呈现。
/// 软链接按链接目标取信息。
struct FileInfoSheet: View {
    let accountId: String
    let file: FileItem

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ViewModelScope({ CoreGraph.shared.fileInfoViewModel(accountId: accountId, uri: file.linkedUri) }) { host in
                FileInfoContent(host: host, fallback: file)
            }
            .navigationTitle("详细信息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}
