import SwiftUI
import OpenCloudreveCore

/// 管理端任务队列：队列指标、任务列表、清理旧任务
struct AdminQueueScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.adminQueueViewModel(accountId: accountId) }) { host in
            AdminQueueContent(host: host)
        }
        .navigationTitle("任务队列")
    }
}
