import SwiftUI
import OpenCloudreveCore

/// 压缩包预览：目录树浏览、文件名编码、解压全部/所选到指定文件夹
struct ArchiveViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    var body: some View {
        ViewModelScope({ CoreGraph.shared.viewerArchiveViewModel(params: request.params) }) { host in
            ArchiveViewerContent(host: host, chrome: chrome, accountId: request.accountId)
        }
    }
}
