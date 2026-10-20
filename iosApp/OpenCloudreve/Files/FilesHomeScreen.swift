import SwiftUI
import OpenCloudreveCore

/// 文件 Tab 侧栏/首页：容量、位置、固定项、打开分享链接。
/// 借用“我的文件”根目录的 `FileListViewModel` 取用户资料（固定项）与容量（随列表刷新）。
struct FilesHomeScreen: View {
    let accountId: String
    @Binding var selection: String?

    var body: some View {
        ViewModelScope({
            CoreGraph.shared.fileListViewModel(accountId: accountId, uri: CrUri.companion.my(uid: nil).description())
        }) { host in
            FilesHomeContent(accountId: accountId, host: host, selection: $selection)
        }
        .navigationTitle("文件")
    }
}
