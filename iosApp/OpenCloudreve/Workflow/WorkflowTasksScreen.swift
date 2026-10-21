import SwiftUI
import OpenCloudreveCore

/// “任务”Tab 的“后台任务”段（不自带导航栈，放进 TasksTab 的 NavigationStack）
struct WorkflowTasksScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({
            CoreGraph.shared.workflowListViewModel(accountId: accountId, initialCategory: TaskCategory.shared.GENERAL)
        }) { host in
            WorkflowTasksContent(accountId: accountId, host: host)
        }
    }
}
