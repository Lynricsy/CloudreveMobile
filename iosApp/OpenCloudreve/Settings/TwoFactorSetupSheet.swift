import SwiftUI

/// 开启两步验证：扫描二维码或手动输入密钥，再输入验证码确认
struct TwoFactorSetupSheet: View {
    let secret: String
    let otpauthUrl: String
    let busy: Bool
    let error: String?
    let confirm: (String) -> Void
    let cancel: () -> Void
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TwoFactorQRCode(text: otpauthUrl)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                } footer: {
                    Text("用验证器 App（如“密码”App、Google Authenticator）扫描二维码。")
                }

                Section("密钥") {
                    Text(verbatim: secret)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                        .accessibilityIdentifier("twofa.secret")
                    Button("拷贝密钥", systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = secret
                    }
                    if let url = URL(string: otpauthUrl) {
                        Link(destination: url) {
                            Label("在验证器 App 中打开", systemImage: "arrow.up.forward.app")
                        }
                    }
                }

                Section("验证码") {
                    TextField("6 位验证码", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .accessibilityIdentifier("twofa.code")
                    if let error {
                        ErrorRow(message: error)
                    }
                }

                Section {
                    PrimaryActionButton(title: "启用", busy: busy) { confirm(code) }
                        .disabled(code.count != 6 || busy)
                        .accessibilityIdentifier("twofa.confirm")
                }
            }
            .navigationTitle("启用两步验证")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", systemImage: "xmark", action: cancel)
                        .accessibilityIdentifier("twofa.cancel")
                }
            }
        }
        .interactiveDismissDisabled(busy)
    }
}
