import SwiftUI
import OpenCloudreveCore

/// 文件版本列表（设为当前、删除、预览指定版本）。不自带导航栈：可推入任意栈或放进 `VersionsSheet`。
/// 软链接按链接目标（`linkedUri`）管理版本。
struct VersionsScreen: View {
    let accountId: String
    let file: FileItem

    var body: some View {
        ViewModelScope({ CoreGraph.shared.versionsViewModel(accountId: accountId, uri: file.linkedUri) }) { host in
            VersionsContent(accountId: accountId, host: host, fallback: file)
        }
        .navigationTitle("版本")
        .navigationBarTitleDisplayMode(.inline)
    }
}
