import SwiftUI

/// 以 sheet 呈现的分享浏览（自带导航栈与关闭按钮）
struct ShareVisitSheet: View {
    let accountId: String
    let link: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ShareVisitScreen(accountId: accountId, link: link)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭", role: .close) { dismiss() }
                    }
                }
        }
    }
}
