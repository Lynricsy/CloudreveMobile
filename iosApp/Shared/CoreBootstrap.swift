import Foundation
import OpenCloudreveCore

/// 启动共享核心（主 App 与扩展各自在进程启动时调用一次，重复调用无副作用）。
///
/// App Group 与 Keychain 访问组取自 Info.plist 的 `AppGroupIdentifier` / `KeychainAccessGroup`
/// （后者在 project.yml 中为 `$(AppIdentifierPrefix)plus.ling.opencloudreve.shared`）。
/// 未签名构建拿不到这两个 entitlement 时由共享层回退：数据库放 Application Support、Keychain 不带访问组。
enum CoreBootstrap {
    static func start(bundle: Bundle = .main) {
        guard !CoreGraph.shared.isStarted else { return }
        let info = bundle.infoDictionary ?? [:]
        let appGroup = (info["AppGroupIdentifier"] as? String).flatMap(nonEmpty) ?? AppGroup.identifier
        let keychainGroup = (info["KeychainAccessGroup"] as? String).flatMap(nonEmpty)
        let version = info["CFBundleShortVersionString"] as? String ?? "0"
        CoreGraph.shared.startIos(appGroupId: appGroup, keychainAccessGroup: keychainGroup, appVersion: version)
    }

    /// 空串或未展开的构建变量视为未配置
    private static func nonEmpty(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty || trimmed.contains("$(") ? nil : trimmed
    }
}
