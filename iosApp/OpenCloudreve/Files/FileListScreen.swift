import SwiftUI
import OpenCloudreveCore

/// 一个目录（我的文件、回收站、与我共享、分享内目录、他人文件 `cloudreve://<uid>@my`）的列表页。
/// 不自带 `NavigationStack`：在任意导航栈中推入即可，子目录以 `NavigationLink` 推入同一栈。
struct FileListScreen: View {
    let accountId: String
    let uri: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.fileListViewModel(accountId: accountId, uri: uri) }) { list in
            ViewModelScope({ CoreGraph.shared.fileActionViewModel(accountId: accountId) }) { actions in
                FileListContent(accountId: accountId, list: list, actions: actions)
            }
        }
        .navigationTitle(FileFormat.folderTitle(uri))
    }
}
