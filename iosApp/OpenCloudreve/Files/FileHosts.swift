import OpenCloudreveCore

typealias FileListHost = ViewModelHost<FileListState, any FileListIntent, any FileListEffect>
typealias FileActionHost = ViewModelHost<FileActionState, any FileActionIntent, any FileActionEffect>
typealias FolderPickerHost = ViewModelHost<FolderPickerState, any FolderPickerIntent, KotlinNothing>
typealias FileInfoHost = ViewModelHost<FileInfoState, any FileInfoIntent, any FileInfoEffect>

// 可写计算属性：读状态、写转成意图，配合 `@Bindable` 得到绑定

extension ViewModelHost where S == FileListState, I == any FileListIntent {
    /// 多选集合（文件 path）；写入时按差集逐个切换
    var selectionPaths: Set<String> {
        get { state.selection }
        set {
            let current = state.selection
            for path in current.symmetricDifference(newValue) {
                send(FileListIntentToggleSelection(path: path))
            }
        }
    }

    /// 排序字段（来自 `props.order_by_options`）
    var orderBy: String {
        get { state.orderBy }
        set { send(FileListIntentSetSort(orderBy: newValue, orderDirection: state.orderDirection)) }
    }

    /// 排序方向（`asc` / `desc`）
    var orderDirection: String {
        get { state.orderDirection }
        set { send(FileListIntentSetSort(orderBy: state.orderBy, orderDirection: newValue)) }
    }

    /// 视图模式：`list` 或 `grid`（服务端的 `gallery` 按网格显示）
    var viewMode: String {
        get { state.viewMode == ExplorerView.companion.VIEW_LIST ? ExplorerView.companion.VIEW_LIST : ExplorerView.companion.VIEW_GRID }
        set { send(FileListIntentSetViewMode(viewMode: newValue)) }
    }

    var showThumbnails: Bool {
        get { state.showThumbnails }
        set { send(FileListIntentSetThumbnails(enabled: newValue)) }
    }
}

extension ViewModelHost where S == FileActionState, I == any FileActionIntent {
    /// 40073 锁冲突提示。关闭只能通过按钮（各自发送意图），这里的写入不发意图，
    /// 避免“强制解锁并重试”与系统关闭弹窗的写入先后不定时把待重放的操作清掉
    var isLockConflictPresented: Bool {
        get { state.lockConflict != nil }
        set { _ = newValue }
    }

    /// 40081 部分失败明细（同上，由“好”按钮发送 `DismissFailures`）
    var isFailuresPresented: Bool {
        get { !state.partialFailures.isEmpty }
        set { _ = newValue }
    }

    func run(_ operation: FileOperation) {
        send(FileActionIntentRun(operation: operation))
    }
}
