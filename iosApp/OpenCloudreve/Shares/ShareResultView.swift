import SwiftUI
import UIKit
import OpenCloudreveCore

/// 保存成功后的链接结果：二维码、链接（含密码）、密码、复制、系统分享、完成
struct ShareResultView: View {
    let result: ShareEditResult
    let name: String

    @State private var copied = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section {
                ShareQRCodeImage(text: result.link)
                    .frame(width: 200, height: 200)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("shareResult.qr")
            }
            .listRowBackground(Color.clear)

            Section {
                Text(verbatim: result.link)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .accessibilityIdentifier("shareResult.link")
                if let password = result.password, !password.isEmpty {
                    LabeledContent("密码") {
                        Text(verbatim: password)
                            .font(.body.monospaced())
                            .textSelection(.enabled)
                    }
                }
            } header: {
                Text(verbatim: name)
            } footer: {
                if let password = result.password, !password.isEmpty {
                    Text("链接中已包含密码，收到链接的人无需再输入。")
                }
            }

            Section {
                Button {
                    UIPasteboard.general.string = result.link
                    copied = true
                } label: {
                    if copied {
                        Label("已复制", systemImage: "checkmark")
                    } else {
                        Label("复制链接", systemImage: "doc.on.doc")
                    }
                }
                .accessibilityIdentifier("shareResult.copy")
                shareButton
                    .accessibilityIdentifier("shareResult.share")
            }
        }
        .sensoryFeedback(.success, trigger: copied)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }
                    .accessibilityIdentifier("shareResult.done")
            }
        }
    }

    @ViewBuilder
    private var shareButton: some View {
        if let url = URL(string: result.link) {
            SwiftUI.ShareLink(item: url, subject: Text(verbatim: name)) {
                Label("发送链接", systemImage: "square.and.arrow.up")
            }
        } else {
            SwiftUI.ShareLink(item: result.link, subject: Text(verbatim: name)) {
                Label("发送链接", systemImage: "square.and.arrow.up")
            }
        }
    }
}
