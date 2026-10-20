import SwiftUI

/// 折叠显示的 JSON 文本（等宽、可选择）
struct AdminJSONDisclosure: View {
    let title: LocalizedStringKey
    let json: String

    var body: some View {
        DisclosureGroup(title) {
            ScrollView(.horizontal) {
                Text(verbatim: json)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
    }
}
