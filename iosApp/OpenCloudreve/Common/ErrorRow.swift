import SwiftUI

/// 表单内的错误提示行（图标 + 红色文字，不只依赖颜色传达含义）
struct ErrorRow: View {
    let message: String

    var body: some View {
        Label {
            Text(message)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
        }
        .foregroundStyle(.red)
        .accessibilityIdentifier("error.message")
    }
}
