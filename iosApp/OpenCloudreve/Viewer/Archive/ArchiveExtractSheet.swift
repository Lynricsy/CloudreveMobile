import SwiftUI
import OpenCloudreveCore

/// 创建解压任务（`POST /workflow/extract`）：目标文件夹、密码、节点
struct ArchiveExtractSheet: View {
    let accountId: String
    let request: ArchiveExtractRequest

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.archiveTaskViewModel(
                    accountId: accountId,
                    mode: .extract,
                    srcUris: [request.fileUri],
                    dstUri: request.parentUri,
                    fileMask: request.mask
                )
            }) { host in
                ArchiveExtractForm(host: host, accountId: accountId, encoding: request.encoding)
            }
        }
    }
}
