import SwiftUI
import OpenCloudreveCore

/// 文件管理：视图设置同步、版本保留
struct FileManagementSection: View {
    @Bindable var settings: SettingsHost

    var body: some View {
        Section {
            Toggle(isOn: $settings.syncViewSettings) {
                Label("同步文件视图设置", systemImage: "rectangle.grid.2x2")
            }
            .disabled(settings.state.saving == .viewSync || settings.state.user == nil)
            .accessibilityIdentifier("settings.viewSync")

            NavigationLink {
                VersionRetentionScreen(settings: settings)
            } label: {
                LabeledContent {
                    Text(settings.state.versionRetentionEnabled ? "已开启" : "已关闭")
                } label: {
                    Label("版本保留", systemImage: "clock.arrow.circlepath")
                }
            }
            .accessibilityIdentifier("settings.versionRetention")
        } header: {
            Text("文件管理")
        } footer: {
            Text("开启同步后，排序、列表/网格等视图设置会保存到服务器，在其他设备上保持一致。")
        }
    }
}
