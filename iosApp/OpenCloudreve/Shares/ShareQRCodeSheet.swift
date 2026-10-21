import SwiftUI

/// 分享列表“二维码”：显示链接二维码，可发送链接
struct ShareQRCodeSheet: View {
    let link: String
    let name: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                ShareQRCodeImage(text: link)
                    .frame(width: 240, height: 240)
                    .accessibilityIdentifier("shareQR.image")
                Text(verbatim: link)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
                    .padding(.horizontal)
                if let url = URL(string: link) {
                    SwiftUI.ShareLink(item: url) {
                        Label("发送链接", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.glass)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(Text(verbatim: name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
