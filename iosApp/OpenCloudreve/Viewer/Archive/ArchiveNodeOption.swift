import Foundation

/// 可选的处理节点（`GET /workflow/nodes?capability=2`）
struct ArchiveNodeOption: Identifiable, Hashable {
    let id: String
    let name: String
}
