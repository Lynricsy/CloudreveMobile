import SwiftUI

/// 当前账户的站点主题色作为全局强调色（`.tint`）；未设置或解析失败时沿用 App 默认强调色
struct AccountTint: ViewModifier {
    let accountId: String
    @AppStorage private var light: String
    @AppStorage private var dark: String
    @Environment(\.colorScheme) private var colorScheme

    init(accountId: String) {
        self.accountId = accountId
        _light = AppStorage(wrappedValue: "", ThemeTintStore.lightKey(accountId))
        _dark = AppStorage(wrappedValue: "", ThemeTintStore.darkKey(accountId))
    }

    func body(content: Content) -> some View {
        content
            .tint(ThemeColor.parse(colorScheme == .dark ? dark : light))
            .task(id: accountId) {
                await ThemeTintStore.refresh(accountId: accountId)
            }
    }
}
