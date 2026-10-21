import SwiftUI
import OpenCloudreveCore

/// 创建压缩任务（`POST /workflow/archive`，自带导航栈）：压缩包名、所在目录、节点。
/// `initialName` 为预填的压缩包名（nil 用默认 `archive.zip`）。
struct ArchiveCreateSheet: View {
    let accountId: String
    let srcUris: [String]
    let dstUri: String
    var initialName: String? = nil
    let onCreated: @MainActor () -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.archiveTaskViewModel(
                    accountId: accountId,
                    mode: .compress,
                    srcUris: srcUris,
                    dstUri: dstUri,
                    fileMask: []
                )
            }) { host in
                ArchiveCreateForm(host: host, accountId: accountId, initialName: initialName, onCreated: onCreated)
            }
        }
    }
}
