import SwiftUI

/// 尚未实现内容的 Tab 根页：带导航栏的空状态
struct TabPlaceholder: View {
    let title: LocalizedStringKey
    let systemImage: String
    let message: LocalizedStringKey
    let identifier: String

    var body: some View {
        NavigationStack {
            ScrollView {
                ContentUnavailableView(title, systemImage: systemImage, description: Text(message))
                    .containerRelativeFrame(.vertical)
            }
            .navigationTitle(title)
            .accessibilityIdentifier(identifier)
        }
    }
}
