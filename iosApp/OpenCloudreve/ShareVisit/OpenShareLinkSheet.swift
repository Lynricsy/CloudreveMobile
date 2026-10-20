import SwiftUI

/// “打开分享链接”：输入或粘贴链接后在同一 sheet 内浏览分享
struct OpenShareLinkSheet: View {
    let accountId: String

    @Environment(\.dismiss) private var dismiss
    @State private var link = ""
    @State private var opened: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("分享链接或 ID", text: $link, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onSubmit(open)
                        .accessibilityIdentifier("shareLink.field")
                } footer: {
                    Text(verbatim: "https://example.com/s/AbCd/password")
                }
                Section {
                    PrimaryActionButton(title: "打开", action: open)
                        .disabled(trimmed.isEmpty)
                }
            }
            .navigationTitle("打开分享链接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) { dismiss() }
                }
            }
            .navigationDestination(item: $opened) { link in
                ShareVisitScreen(accountId: accountId, link: link)
            }
        }
    }

    private var trimmed: String {
        link.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func open() {
        guard !trimmed.isEmpty else { return }
        opened = trimmed
    }
}
