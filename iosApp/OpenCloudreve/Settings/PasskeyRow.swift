import SwiftUI
import OpenCloudreveCore

struct PasskeyRow: View {
    let passkey: Passkey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label {
                Text(verbatim: passkey.name.isEmpty ? "Passkey" : passkey.name)
            } icon: {
                Image(systemName: "person.badge.key")
            }
            if let created = SettingsFormat.dateTime(passkey.createdAt) {
                Text("添加于 \(created)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let used = SettingsFormat.dateTime(passkey.usedAt) {
                Text("上次使用 \(used)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
