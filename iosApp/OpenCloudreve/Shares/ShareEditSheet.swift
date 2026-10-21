import SwiftUI
import OpenCloudreveCore

/// 新建（传 `fileUri`）或编辑（传 `shareId`）分享。自带导航栈；保存成功后在同一 sheet 内切换到链接结果页并调用 `onSaved`。
struct ShareEditSheet: View {
    let accountId: String
    let fileUri: String?
    let shareId: String?
    let onSaved: @MainActor () -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.shareEditViewModel(accountId: accountId, fileUri: fileUri, shareId: shareId)
            }) { host in
                ShareEditContent(host: host, onSaved: onSaved)
            }
        }
    }
}
