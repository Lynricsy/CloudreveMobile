import SwiftUI
import OpenCloudreveCore

/// 外观：界面语言（同步到服务端）与站点主题色
struct AppearanceSection: View {
    @Bindable var settings: SettingsHost

    var body: some View {
        Section {
            Picker(selection: $settings.language) {
                ForEach(settings.state.supportedLanguages, id: \.self) { code in
                    Text(verbatim: AppLanguage.displayName(serverCode: code)).tag(code)
                }
            } label: {
                Label("界面语言", systemImage: "globe")
            }
            .disabled(settings.state.saving == .language)
            .accessibilityIdentifier("settings.language")

            if !settings.state.themeOptions.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Label("主题色", systemImage: "paintpalette")
                    ThemeColorPicker(
                        options: settings.state.themeOptions,
                        selected: settings.state.selectedTheme,
                        disabled: settings.state.saving != nil,
                        select: { settings.send(SettingsIntentSetTheme(themeKey: $0)) }
                    )
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text("外观")
        } footer: {
            Text("界面语言同时用于服务端发送的邮件，重新打开 App 后生效。")
        }
    }
}
