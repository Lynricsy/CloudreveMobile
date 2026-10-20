import FileProvider
import OpenCloudreveCore
import os

/// 账户 ↔ File Provider 域：每个已登录账户一个域（标识 = 账户 id，显示名 “<昵称> @ <host>”），
/// 账户被移除（登出）时删除对应的域。跟随 `AccountRepository.accounts` 自动对齐，启动时调用一次 [start]。
@MainActor
enum FileProviderDomains {
    /// 一个应存在的域
    struct Spec: Equatable, Sendable {
        let id: String
        let displayName: String
    }

    private static var task: Task<Void, Never>?
    nonisolated private static let log = Logger(subsystem: "plus.ling.opencloudreve", category: "FileProvider")

    static func start(accounts: AccountRepository) {
        guard task == nil else { return }
        task = Task {
            var last: [Spec]?
            for await list in accounts.accounts {
                let specs = list.map { Spec(id: $0.id, displayName: displayName(nickname: $0.nickname, email: $0.email, host: $0.host)) }
                guard specs != last else { continue }
                last = specs
                await reconcile(specs)
            }
        }
    }

    /// 域显示名：昵称为空时用邮箱
    nonisolated static func displayName(nickname: String, email: String, host: String) -> String {
        let name = nickname.trimmingCharacters(in: .whitespaces)
        return "\(name.isEmpty ? email : name) @ \(host)"
    }

    /// 删除多余的域，新增/更新应有的域（模拟器或未签名构建可能拒绝，记录后忽略）
    private nonisolated static func reconcile(_ specs: [Spec]) async {
        let wanted = Set(specs.map(\.id))
        do {
            for domain in try await NSFileProviderManager.domains() where !wanted.contains(domain.identifier.rawValue) {
                try await NSFileProviderManager.remove(domain)
            }
        } catch {
            log.error("同步 File Provider 域失败：\(error.localizedDescription, privacy: .public)")
        }
        for spec in specs {
            let domain = NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier(rawValue: spec.id), displayName: spec.displayName)
            do {
                try await NSFileProviderManager.add(domain)
            } catch {
                log.error("添加 File Provider 域失败：\(error.localizedDescription, privacy: .public)")
            }
        }
    }
}
