/// 过滤条件的一个可选值（`value` 为空串表示“全部”）
struct AdminOption: Identifiable, Hashable {
    let value: String
    let label: String
    var id: String { value }
}
