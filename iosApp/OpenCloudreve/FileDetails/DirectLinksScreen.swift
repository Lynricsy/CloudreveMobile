import SwiftUI
import OpenCloudreveCore

/// 永久直链：生成（批量）与管理单个文件已有的直链。不自带导航栈：可推入任意栈或放进 `DirectLinksSheet`。
struct DirectLinksScreen: View {
    let accountId: String
    let uris: [String]

    var body: some View {
        ViewModelScope({ CoreGraph.shared.directLinksViewModel(accountId: accountId, uris: uris) }) { host in
            DirectLinksContent(host: host)
        }
        .navigationTitle("直链")
        .navigationBarTitleDisplayMode(.inline)
    }
}
