import SwiftUI

/// 列表搜索框对应的过滤条件键（多个时用搜索范围切换）
struct AdminSearchField: Hashable {
    let key: String
    let title: LocalizedStringKey

    static func == (lhs: AdminSearchField, rhs: AdminSearchField) -> Bool { lhs.key == rhs.key }
    func hash(into hasher: inout Hasher) { hasher.combine(key) }
}
