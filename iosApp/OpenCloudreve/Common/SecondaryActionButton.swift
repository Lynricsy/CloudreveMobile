import SwiftUI

/// 表单次要操作按钮：通栏 `.glass`
struct SecondaryActionButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .frame(maxWidth: .infinity, minHeight: 24)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}
