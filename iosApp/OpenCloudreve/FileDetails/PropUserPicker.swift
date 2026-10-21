import SwiftUI
import OpenCloudreveCore

/// 用户类型属性：显示已解析的用户，输入至少 2 个字符搜索并选择
struct PropUserPicker: View {
    let entry: CustomPropEntry
    /// 当前值对应的用户（`resolvedUsers`）
    let current: User?
    let results: [User]
    let searching: Bool
    let editable: Bool
    let search: (String) -> Void
    let pick: (String) -> Void

    @State private var keyword = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if let current {
                    Text(verbatim: display(current))
                } else if entry.value.isEmpty {
                    Text("未设置")
                        .foregroundStyle(.secondary)
                } else {
                    Text(verbatim: entry.value)
                }
                Spacer(minLength: 0)
                if editable && !entry.value.isEmpty {
                    Button("清除") { pick("") }
                        .buttonStyle(.borderless)
                }
            }
            if editable {
                HStack {
                    TextField("搜索用户", text: $keyword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if searching {
                        ProgressView()
                    }
                }
                .onChange(of: keyword) { _, newValue in
                    if newValue.count >= 2 { search(newValue) }
                }
                if keyword.count >= 2 {
                    ForEach(results, id: \.id) { user in
                        Button {
                            keyword = ""
                            pick(user.id)
                        } label: {
                            Text(verbatim: display(user))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
        }
    }

    /// 昵称 · 邮箱（他人信息可能没有邮箱）；都没有时显示用户 id
    private func display(_ user: User) -> String {
        let text = [user.nickname.isEmpty ? nil : user.nickname, user.email].compactMap { $0 }.joined(separator: " · ")
        return text.isEmpty ? user.id : text
    }
}
