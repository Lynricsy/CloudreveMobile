import SwiftUI

/// 两步验证开关
struct TwoFactorSection: View {
    let enabled: Bool
    let busy: Bool
    let enable: () -> Void
    let disable: () -> Void

    var body: some View {
        Section {
            LabeledContent {
                if busy {
                    ProgressView()
                } else {
                    Text(enabled ? "已开启" : "未开启")
                }
            } label: {
                Label("两步验证", systemImage: "lock.rotation")
            }
            if enabled {
                Button("关闭两步验证", role: .destructive, action: disable)
                    .disabled(busy)
                    .accessibilityIdentifier("security.disable2fa")
            } else {
                Button("启用两步验证", action: enable)
                    .disabled(busy)
                    .accessibilityIdentifier("security.enable2fa")
            }
        } header: {
            Text("两步验证")
        } footer: {
            Text("登录时除密码外还需输入验证器 App 生成的 6 位验证码。")
        }
    }
}
