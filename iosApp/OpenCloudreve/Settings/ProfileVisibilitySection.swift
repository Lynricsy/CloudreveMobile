import SwiftUI
import OpenCloudreveCore

/// 个人主页上展示哪些分享
struct ProfileVisibilitySection: View {
    @Bindable var settings: SettingsHost

    var body: some View {
        Section {
            Picker(selection: $settings.shareLinksInProfile) {
                Text("仅公开分享").tag(ShareLinksInProfile.shared.PUBLIC_ONLY)
                Text("全部分享").tag(ShareLinksInProfile.shared.ALL)
                Text("不展示").tag(ShareLinksInProfile.shared.HIDE)
            } label: {
                Label("个人主页展示的分享", systemImage: "person.crop.square")
            }
            .disabled(settings.state.saving == .shareLinksInProfile || settings.state.settings == nil)
        } header: {
            Text("隐私")
        } footer: {
            Text("其他用户访问你的个人主页时能看到的分享链接。")
        }
    }
}
