import SwiftUI
import OpenCloudreveCore

/// “任务”Tab：传输列表；接入后台任务（`TasksFeatures.backgroundTasks`）后顶部出现“传输 / 后台任务”分段
struct TasksTab: View {
    let account: Account

    @State private var segment: TasksSegment = .transfers

    var body: some View {
        let background = TasksFeatures.installed.backgroundTasks
        NavigationStack {
            Group {
                switch segment {
                case .transfers:
                    TransferListScreen(accountId: account.id)
                case .background:
                    if let background { background(account.id) }
                }
            }
            .navigationTitle("任务")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if background != nil {
                    ToolbarItem(placement: .principal) {
                        Picker("任务类型", selection: $segment) {
                            Text("传输").tag(TasksSegment.transfers)
                            Text("后台任务").tag(TasksSegment.background)
                        }
                        .pickerStyle(.segmented)
                        .fixedSize()
                    }
                }
            }
        }
    }
}

/// 任务 Tab 的分段
enum TasksSegment: Hashable {
    case transfers
    case background
}

/// 任务 Tab 中由其它阶段接入的内容
struct TasksFeatures {
    /// P7：后台任务（工作流）列表，参数为账户 id；视图不自带导航栈
    var backgroundTasks: (@MainActor (String) -> AnyView)?

    @MainActor static let installed = TasksFeatures()
}

/// 传输列表（不自带导航栈）
struct TransferListScreen: View {
    let accountId: String

    var body: some View {
        ViewModelScope({ CoreGraph.shared.transferListViewModel(accountId: accountId) }) { host in
            TransferListContent(host: host)
        }
    }
}
