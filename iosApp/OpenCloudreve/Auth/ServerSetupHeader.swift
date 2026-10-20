import SwiftUI

/// 服务器地址页顶部说明
struct ServerSetupHeader: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "externaldrive.connected.to.line.below")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text("连接到你的 OpenCloudreve")
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text("输入你的站点地址，登录后即可在手机上管理文件。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
