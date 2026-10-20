import Foundation
import OpenCloudreveCore

/// 某个文件可用的预览器（原生优先）与打开决定（`ViewerResolver`：偏好 → 唯一候选 → 站点默认映射 → 让用户选择）
struct ViewerCatalog: Sendable {
    let candidates: [ViewerOption]
    let decision: Decision

    enum Decision: Sendable {
        /// 直接用该预览器打开
        case open(ViewerOption)
        /// 多个候选：让用户选择
        case choose
        /// 没有可用预览器：下载后交给系统
        case none
    }

    /// 加载失败（文案已本地化）
    struct LoadFailure: Error, Sendable {
        let message: String
    }

    /// 拉取站点 explorer 配置与当前用户（组权限）后计算候选
    nonisolated static func load(
        accountId: String,
        fileExtension: String,
        preferredViewerId: String?
    ) async -> Result<ViewerCatalog, LoadFailure> {
        do {
            let explorer = try await CoreGraph.shared.siteConfig().explorer(accountId: accountId, forceRefresh: false)
            let user = try await CoreGraph.shared.userSettings(accountId: accountId).me()
            let resolver = ViewerResolver(
                explorer: explorer,
                groupPermission: user.group?.permission,
                platform: ViewerResolver.companion.PLATFORM_MOBILE
            )
            let all = resolver.candidates(ext: fileExtension).map(ViewerOption.init(viewer:))
            let candidates = all.filter(\.isNative) + all.filter { !$0.isNative }
            let decision: Decision
            switch onEnum(of: resolver.resolve(ext: fileExtension, preferredViewerId: preferredViewerId)) {
            case let .open(open):
                decision = .open(ViewerOption(viewer: open.viewer))
            case .choose:
                decision = .choose
            case .noViewer:
                decision = .none
            }
            return .success(ViewerCatalog(candidates: candidates, decision: decision))
        } catch {
            return .failure(LoadFailure(message: FileErrors.message(error)))
        }
    }
}
