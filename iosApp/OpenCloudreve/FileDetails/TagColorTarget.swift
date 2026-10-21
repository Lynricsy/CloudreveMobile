/// 正在修改颜色的标签（`sheet(item:)` 用）
struct TagColorTarget: Identifiable {
    let name: String
    let color: String?

    var id: String { name }
}
