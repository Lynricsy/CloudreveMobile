import SwiftUI
import OpenCloudreveCore

/// 设置与（管理员）管理入口
struct MeEntriesSection: View {
    let accountId: String
    let settings: SettingsHost
    let isAdmin: Bool

    var body: some View {
        Section {
            NavigationLink {
                SettingsScreen(accountId: accountId, settings: settings)
            } label: {
                Label("设置", systemImage: "gearshape")
            }
            .accessibilityIdentifier("me.settings")

            if isAdmin {
                NavigationLink {
                    AdminHomeScreen(accountId: accountId)
                } label: {
                    Label("管理", systemImage: "gauge.with.dots.needle.67percent")
                }
                .accessibilityIdentifier("me.admin")
            }
        }
    }
}
