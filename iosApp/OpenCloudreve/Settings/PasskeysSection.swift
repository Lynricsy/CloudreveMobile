import SwiftUI
import OpenCloudreveCore

/// Passkey 列表：查看与删除；iOS 端不支持在 App 内添加，引导到网页端
struct PasskeysSection: View {
    let passkeys: [Passkey]
    let deleting: Bool
    let openingWeb: Bool
    let delete: (String) -> Void
    let addOnWeb: @MainActor () -> Void
    @State private var pending: Passkey?

    var body: some View {
        Section {
            if passkeys.isEmpty {
                Text("尚未添加 Passkey")
                    .foregroundStyle(.secondary)
            }
            ForEach(passkeys, id: \.id) { passkey in
                PasskeyRow(passkey: passkey)
                    .swipeActions {
                        Button("删除", systemImage: "trash", role: .destructive) { pending = passkey }
                    }
                    .contextMenu {
                        Button("删除", systemImage: "trash", role: .destructive) { pending = passkey }
                    }
                    .disabled(deleting)
            }
            Button(action: addOnWeb) {
                HStack {
                    Label("在网页中添加 Passkey", systemImage: "safari")
                    Spacer()
                    if openingWeb {
                        ProgressView()
                    }
                }
            }
            .disabled(openingWeb)
        } header: {
            Text("Passkey")
        } footer: {
            Text("iOS 端暂不支持直接添加 Passkey，请在网页端添加后用于登录。")
        }
        .confirmationDialog("删除此 Passkey？", isPresented: isConfirming, titleVisibility: .visible, presenting: pending) { passkey in
            Button("删除", role: .destructive) { delete(passkey.id) }
        } message: { _ in
            Text("删除后将无法再用它登录。")
        }
    }

    private var isConfirming: Binding<Bool> {
        Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })
    }
}
