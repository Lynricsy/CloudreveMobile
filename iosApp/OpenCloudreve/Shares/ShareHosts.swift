import OpenCloudreveCore

typealias ShareListHost = ViewModelHost<ShareListState, any ShareListIntent, any ShareListEffect>
typealias ShareEditHost = ViewModelHost<ShareEditState, any ShareEditIntent, any ShareEditEffect>

// 可写计算属性：读状态、写转成意图，配合 `@Bindable` 得到绑定

extension ViewModelHost where S == ShareListState, I == any ShareListIntent {
    /// 多选集合（分享 id）；写入时按差集逐个切换
    var selectionIds: Set<String> {
        get { state.selection }
        set {
            for id in state.selection.symmetricDifference(newValue) {
                send(ShareListIntentToggleSelection(id: id))
            }
        }
    }

    /// 排序字段：nil 按创建顺序，其余见 `ShareOrderBy`
    var sortField: String? {
        get { state.orderBy }
        set { send(ShareListIntentSetSort(orderBy: newValue, orderDirection: state.orderDirection)) }
    }

    /// 排序方向（`asc` / `desc`）
    var sortDirection: String {
        get { state.orderDirection }
        set { send(ShareListIntentSetSort(orderBy: state.orderBy, orderDirection: newValue)) }
    }
}

extension ViewModelHost where S == ShareEditState, I == any ShareEditIntent {
    var isPrivate: Bool {
        get { state.isPrivate }
        set { send(ShareEditIntentSetPrivate(enabled: newValue)) }
    }

    var password: String {
        get { state.password }
        set { send(ShareEditIntentSetPassword(password: newValue)) }
    }

    var shareView: Bool {
        get { state.shareView }
        set { send(ShareEditIntentSetShareView(enabled: newValue)) }
    }

    var showReadme: Bool {
        get { state.showReadme }
        set { send(ShareEditIntentSetShowReadme(enabled: newValue)) }
    }

    /// 自定义下载次数输入：只保留数字；清空记为 0（无效，提交按钮随之禁用）
    var downloadLimitText: String {
        get {
            guard let limit = state.downloadLimit?.int32Value, limit > 0 else { return "" }
            return String(limit)
        }
        set {
            let digits = String(newValue.filter { ("0"..."9").contains($0) }.prefix(6))
            send(ShareEditIntentSetDownloadLimit(limit: KotlinInt(int: Int32(digits) ?? 0)))
        }
    }
}
