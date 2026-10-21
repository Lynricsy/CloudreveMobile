import SwiftUI
import OpenCloudreveCore

/// 标签与元数据编辑（1..n 个文件）。不自带导航栈：可推入任意栈或放进 `MetadataEditSheet`。
struct MetadataEditScreen: View {
    let accountId: String
    let uris: [String]

    var body: some View {
        ViewModelScope({ CoreGraph.shared.metadataEditViewModel(accountId: accountId, uris: uris) }) { host in
            MetadataEditContent(host: host)
        }
        .navigationTitle("标签与元数据")
        .navigationBarTitleDisplayMode(.inline)
    }
}
