import SwiftUI

/// 预览已交给浏览器/其他应用时的占位，可再次打开
struct ExternalTargetView: View {
    let systemImage: String
    let title: LocalizedStringKey
    let reopen: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } actions: {
            Button("再次打开", action: reopen)
                .buttonStyle(.glass)
        }
    }
}
