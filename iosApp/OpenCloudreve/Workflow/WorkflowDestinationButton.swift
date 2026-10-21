import SwiftUI

/// 目标目录行：显示目录名，点按打开目录选择器
struct WorkflowDestinationButton: View {
    let title: LocalizedStringKey
    let uri: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            LabeledContent(title) {
                Text(verbatim: FileFormat.folderTitle(uri))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
    }
}
