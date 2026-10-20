import SwiftUI
import OpenCloudreveCore

/// 设置首页（inset grouped 表单）：外观、文件管理，以及安全、WebDAV、存储策略、关于入口。
/// 与“我的”页共用同一个 `SettingsViewModel`，修改后两处同时更新。
struct SettingsScreen: View {
    let accountId: String
    let settings: SettingsHost
    @State private var failure: String?

    var body: some View {
        Form {
            if settings.state.loading && settings.state.user == nil {
                Section {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            }
            if let error = settings.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { settings.send(SettingsIntentRefresh.shared) }
                }
            }

            AppearanceSection(settings: settings)
            FileManagementSection(settings: settings)

            Section {
                NavigationLink {
                    SecurityScreen(accountId: accountId, settings: settings)
                } label: {
                    Label("账户安全", systemImage: "lock.shield")
                }
                .accessibilityIdentifier("settings.security")
                NavigationLink {
                    DavAccountsScreen(accountId: accountId)
                } label: {
                    Label("WebDAV", systemImage: "externaldrive.connected.to.line.below")
                }
                .accessibilityIdentifier("settings.webdav")
                NavigationLink {
                    PoliciesScreen(accountId: accountId)
                } label: {
                    Label("存储策略", systemImage: "cylinder.split.1x2")
                }
                .accessibilityIdentifier("settings.policies")
                NavigationLink {
                    AboutScreen(accountId: accountId, siteUrl: settings.state.siteUrl)
                } label: {
                    Label("关于", systemImage: "info.circle")
                }
                .accessibilityIdentifier("settings.about")
            }
        }
        .navigationTitle("设置")
        .refreshable { settings.send(SettingsIntentRefresh.shared) }
        .task {
            await settings.effects { effect in
                switch onEnum(of: effect) {
                case let .saved(saved):
                    applySaved(saved.field)
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert("保存失败", message: $failure)
    }

    /// 主题保存后立即更新全局强调色；语言保存后在本机覆盖界面语言（下次启动生效）
    private func applySaved(_ field: SettingsField) {
        switch field {
        case .theme:
            let colors = ThemeTintStore.colors(options: settings.state.themeOptions, selected: settings.state.selectedTheme)
            ThemeTintStore.save(accountId: accountId, light: colors.light, dark: colors.dark)
        case .language:
            if let language = settings.state.language {
                AppLanguage.override(serverCode: language)
            }
        default:
            break
        }
    }
}
