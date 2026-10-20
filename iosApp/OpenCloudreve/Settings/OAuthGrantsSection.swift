import SwiftUI
import OpenCloudreveCore

/// 已授权的第三方应用：撤销后该应用需要重新授权
struct OAuthGrantsSection: View {
    let grants: [OAuthGrant]
    let revoking: Bool
    let revoke: (String) -> Void
    @State private var pending: OAuthGrant?

    var body: some View {
        Section {
            if grants.isEmpty {
                Text("没有已授权的应用")
                    .foregroundStyle(.secondary)
            }
            ForEach(grants, id: \.clientId) { grant in
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: grant.clientName.isEmpty ? grant.clientId : grant.clientName)
                    Text("\(grant.scopes.count) 项权限")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if let used = SettingsFormat.dateTime(grant.lastUsedAt) {
                        Text("上次使用 \(used)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button("撤销", systemImage: "xmark.circle", role: .destructive) { pending = grant }
                }
                .contextMenu {
                    Button("撤销授权", systemImage: "xmark.circle", role: .destructive) { pending = grant }
                }
                .disabled(revoking)
            }
        } header: {
            Text("已授权的应用")
        }
        .confirmationDialog("撤销此应用的授权？", isPresented: isConfirming, titleVisibility: .visible, presenting: pending) { grant in
            Button("撤销授权", role: .destructive) { revoke(grant.clientId) }
        } message: { _ in
            Text("该应用将无法再访问你的账户，直到重新授权。")
        }
    }

    private var isConfirming: Binding<Bool> {
        Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })
    }
}
