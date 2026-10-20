import Foundation
import OpenCloudreveCore

/// 压缩包中的一项（从 `ArchiveEntry` 摘出渲染所需字段）
struct ArchiveEntryItem: Identifiable, Hashable {
    let path: String
    let name: String
    let size: Int64
    let isDirectory: Bool
    let selected: Bool

    var id: String { path }

    static func children(of state: ViewerArchiveState) -> [ArchiveEntryItem] {
        state.children.map { entry in
            ArchiveEntryItem(
                path: entry.path,
                name: entry.name,
                size: entry.size,
                isDirectory: entry.isDirectory,
                selected: state.selection.contains(entry.path)
            )
        }
    }
}
