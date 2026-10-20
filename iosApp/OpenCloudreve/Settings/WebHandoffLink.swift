import Foundation
import OpenCloudreveCore

/// App → 网页免登录交接（`WebHandoff`）：生成一次性地址供 `HandoffSafariView` 打开
enum WebHandoffLink {
    enum Outcome: Sendable {
        case opened(URL)
        case failed(String)
    }

    /// 生成交接地址；服务端版本过低或请求失败时给出可展示的文案
    static func resolve(accountId: String, redirect: String) async -> Outcome {
        do {
            let handoff = CoreGraph.shared.webHandoff()
            if try await handoff.availability(accountId: accountId) == .unsupported {
                return .failed(String(localized: "需要服务端 4.19.11 或更高版本"))
            }
            let text = try await handoff.url(accountId: accountId, redirect: redirect)
            guard let url = URL(string: text) else {
                return .failed(String(localized: "无法打开网页"))
            }
            return .opened(url)
        } catch {
            return .failed(String(localized: "无法打开网页：\(error.localizedDescription)", comment: "会话交接失败的原因"))
        }
    }
}
