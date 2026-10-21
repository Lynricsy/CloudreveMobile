import SwiftUI
import OpenCloudreveCore

/// 以 sheet 呈现的版本列表（自带导航栈与完成按钮）
struct VersionsSheet: View {
    let accountId: String
    let file: FileItem

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VersionsScreen(accountId: accountId, file: file)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }
                    }
                }
        }
    }
}
