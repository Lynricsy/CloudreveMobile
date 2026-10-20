import SwiftUI
import OpenCloudreveCore

/// 文件角标：软链接（指向分享）与已分享
struct FileBadges: View {
    let file: FileItem

    var body: some View {
        HStack(spacing: 4) {
            if file.isSymbolic {
                Image(systemName: "link")
                    .accessibilityLabel(Text("快捷方式"))
            }
            if file.shared {
                Image(systemName: "person.2.fill")
                    .accessibilityLabel(Text("已分享"))
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
