/// 列表行包装：以实体整数 id 作为 `ForEach` 与多选的标识
struct AdminRowItem<T: AnyObject>: Identifiable {
    let id: Int32
    let value: T
}
