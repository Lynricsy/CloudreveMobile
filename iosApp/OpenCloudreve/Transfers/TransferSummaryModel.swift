import Observation
import OpenCloudreveCore

/// 当前账户传输汇总（底部迷你栏用）：镜像 `TransferManager.observeSummary`
@MainActor
@Observable
final class TransferSummaryModel {
    private(set) var activeUploads = 0
    private(set) var activeDownloads = 0
    private(set) var progress = 0.0

    var activeCount: Int { activeUploads + activeDownloads }
    var isActive: Bool { activeCount > 0 }

    /// 在视图 `.task` 中调用，任务取消时结束
    func observe(accountId: String) async {
        for await summary in CoreGraph.shared.transferManager().observeSummary(accountId: accountId) {
            activeUploads = Int(summary.activeUploads)
            activeDownloads = Int(summary.activeDownloads)
            progress = min(max(summary.progress, 0), 1)
        }
    }
}
