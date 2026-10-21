import SwiftUI

/// 以 sheet 呈现的标签与元数据编辑（自带导航栈与完成按钮）
struct MetadataEditSheet: View {
    let accountId: String
    let uris: [String]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            MetadataEditScreen(accountId: accountId, uris: uris)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }
                    }
                }
        }
    }
}
