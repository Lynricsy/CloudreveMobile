import SwiftUI

/// 以 sheet 呈现的直链界面（自带导航栈与完成按钮）；菜单“获取直链”使用
struct DirectLinksSheet: View {
    let accountId: String
    let uris: [String]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            DirectLinksScreen(accountId: accountId, uris: uris)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }
                    }
                }
        }
    }
}
