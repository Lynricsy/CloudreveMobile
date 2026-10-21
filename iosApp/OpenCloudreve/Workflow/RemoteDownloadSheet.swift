import SwiftUI
import OpenCloudreveCore

/// 新建离线下载（自带导航栈，以 sheet 呈现）：URL/磁力多行，或种子文件（`torrentUri`）
struct RemoteDownloadSheet: View {
    let accountId: String
    let dstUri: String
    let torrentUri: String?
    let onCreated: @MainActor () -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.remoteDownloadCreateViewModel(accountId: accountId, dstUri: dstUri, torrentUri: torrentUri)
            }) { host in
                RemoteDownloadForm(host: host, accountId: accountId, onCreated: onCreated)
            }
        }
    }
}
