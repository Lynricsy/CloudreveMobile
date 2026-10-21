import SwiftUI
import OpenCloudreveCore

/// 转移存储策略（`POST /workflow/relocate`，自带导航栈）：选择目标策略后提交
struct RelocateSheet: View {
    let accountId: String
    let srcUris: [String]
    let onCreated: @MainActor () -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.archiveTaskViewModel(
                    accountId: accountId,
                    mode: .relocate,
                    srcUris: srcUris,
                    dstUri: "",
                    fileMask: []
                )
            }) { host in
                RelocateForm(host: host, onCreated: onCreated)
            }
        }
    }
}
