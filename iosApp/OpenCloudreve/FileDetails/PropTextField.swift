import SwiftUI
import OpenCloudreveCore

/// 文本 / 数字 / 链接属性：本地编辑，回车或“保存”提交；只读时链接可点
struct PropTextField: View {
    let entry: CustomPropEntry
    let editable: Bool
    let submit: (String) -> Void

    @State private var text: String

    init(entry: CustomPropEntry, editable: Bool, submit: @escaping (String) -> Void) {
        self.entry = entry
        self.editable = editable
        self.submit = submit
        _text = State(initialValue: entry.value)
    }

    var body: some View {
        Group {
            if editable {
                HStack {
                    field
                    if text != entry.value {
                        Button("保存") { submit(text) }
                            .buttonStyle(.borderless)
                    }
                }
            } else if isLink, let url = URL(string: entry.value), url.scheme != nil {
                Link(destination: url) {
                    Text(verbatim: entry.value)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            } else if entry.value.isEmpty {
                Text("未设置")
                    .foregroundStyle(.secondary)
            } else {
                Text(verbatim: entry.value)
                    .textSelection(.enabled)
            }
        }
        .onChange(of: entry.value) { _, newValue in
            text = newValue
        }
    }

    @ViewBuilder
    private var field: some View {
        switch entry.type {
        case CustomPropDefinition.companion.TYPE_NUMBER:
            TextField("输入整数", text: $text)
                .keyboardType(.numbersAndPunctuation)
                .onSubmit { submit(text) }
                .onChange(of: text) { _, newValue in
                    let filtered = newValue.filter { $0.isNumber || $0 == "-" }
                    if filtered != newValue { text = filtered }
                }
        case CustomPropDefinition.companion.TYPE_LINK:
            TextField("链接地址", text: $text)
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { submit(text) }
        default:
            TextField("输入文本", text: $text)
                .onSubmit { submit(text) }
        }
    }

    private var isLink: Bool {
        entry.type == CustomPropDefinition.companion.TYPE_LINK
    }
}
