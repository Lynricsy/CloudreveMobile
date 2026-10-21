import SwiftUI
import OpenCloudreveCore

/// 信息页“直链”分组：已有直链（复制、删除）
struct FileInfoDirectLinksSection: View {
    let links: [DirectLink]
    /// 修改中禁用删除
    let mutating: Bool
    let delete: (_ id: String) -> Void

    var body: some View {
        if !links.isEmpty {
            Section("直链") {
                ForEach(links, id: \.id) { link in
                    LinkCopyRow(
                        link: link.url,
                        note: String(localized: "已下载 \(Int(link.downloaded)) 次", comment: "直链下载次数")
                    )
                    .swipeActions {
                        Button("删除", systemImage: "trash", role: .destructive) {
                            delete(link.id)
                        }
                        .disabled(mutating)
                    }
                    .contextMenu {
                        Button("删除", systemImage: "trash", role: .destructive) {
                            delete(link.id)
                        }
                        .disabled(mutating)
                    }
                }
            }
        }
    }
}
