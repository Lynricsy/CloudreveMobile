import SwiftUI
import OpenCloudreveCore

/// 目录选择器（移动/复制目标、WebDAV 根目录、分享保存位置等），以 sheet 呈现，自带导航栈。
/// 点“选择此文件夹”后回调 `onPick(当前目录 URI)` 并关闭自身。
struct FolderPickerSheet: View {
    let accountId: String
    let initialUri: String
    let title: LocalizedStringKey
    var excludedUris: [String] = []
    let onPick: (String) -> Void

    var body: some View {
        NavigationStack {
            ViewModelScope({
                CoreGraph.shared.folderPickerViewModel(accountId: accountId, initialUri: initialUri, excludedUris: excludedUris)
            }) { host in
                FolderPickerContent(host: host, title: title, onPick: onPick)
            }
        }
    }
}
