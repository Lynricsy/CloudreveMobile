import Foundation
import OpenCloudreveCore

/// 按账户缓存站点主题色（浅色/深色主色），供 `AccountTint` 作为全局 `.tint`。
/// 启动时先用缓存渲染，再从服务端刷新；设置页切换主题后立即写入。
enum ThemeTintStore {
    static func lightKey(_ accountId: String) -> String { "theme.tint.light." + accountId }
    static func darkKey(_ accountId: String) -> String { "theme.tint.dark." + accountId }

    /// 写入选中的主题；找不到选项且主题键本身是颜色时直接用它，否则清空（回到 App 默认强调色）
    static func save(accountId: String, light: String, dark: String) {
        UserDefaults.standard.set(light, forKey: lightKey(accountId))
        UserDefaults.standard.set(dark, forKey: darkKey(accountId))
    }

    /// 由站点主题选项与选中键得到浅色/深色主色
    static func colors(options: [ThemeColorOption], selected: String?) -> (light: String, dark: String) {
        guard let selected, !selected.isEmpty else { return ("", "") }
        if let option = options.first(where: { $0.key == selected }) {
            return (option.lightPrimary, option.darkPrimary)
        }
        return ThemeColor.parse(selected) != nil ? (selected, selected) : ("", "")
    }

    /// 从服务端拉取站点主题与用户偏好并更新缓存；失败时保留旧值
    static func refresh(accountId: String) async {
        guard let colors = await fetchColors(accountId: accountId) else { return }
        save(accountId: accountId, light: colors.light, dark: colors.dark)
    }

    /// 在非隔离上下文中调用共享层（返回值为 Kotlin 对象，不跨越 actor），只把颜色字符串带回
    private nonisolated static func fetchColors(accountId: String) async -> (light: String, dark: String)? {
        guard let basic = try? await CoreGraph.shared.siteConfig().basic(accountId: accountId, forceRefresh: false) else {
            return nil
        }
        let options = SettingsViewModel.companion.parseThemes(themes: basic.themes)
        let preferred = (try? await CoreGraph.shared.userSettings(accountId: accountId).me())?.preferredTheme
        // 与 SettingsState.selectedTheme 一致：用户偏好（且仍在站点选项内）优先，否则站点默认
        let selected: String? = if let preferred, !preferred.isEmpty, options.isEmpty || options.contains(where: { $0.key == preferred }) {
            preferred
        } else {
            basic.defaultTheme
        }
        return colors(options: options, selected: selected)
    }
}
