import Foundation
import OpenCloudreveCore

/// 加载账户站点的文件图标表（`explorer.icons`）；网络失败时用缓存，仍没有则用默认表
enum FileIconLoader {
    nonisolated static func theme(accountId: String, siteUrl: String) async -> FileIconTheme {
        let site = CoreGraph.shared.siteConfig()
        let explorer: SiteExplorer?
        do {
            explorer = try await site.explorer(accountId: accountId, forceRefresh: false)
        } catch {
            explorer = site.cachedExplorer(accountId: accountId)
        }
        return FileIconTheme(explorer: explorer, siteUrl: siteUrl)
    }
}
