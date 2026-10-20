import OpenCloudreveCore

// 预览器 VM 宿主类型与绑定（约定见 local://ios-conventions.md「ViewModelHost 用法」）

typealias ViewerImageHost = ViewModelHost<ViewerImageState, any ViewerImageIntent, KotlinNothing>
typealias ViewerVideoHost = ViewModelHost<ViewerVideoState, any ViewerVideoIntent, KotlinNothing>
typealias ViewerPdfHost = ViewModelHost<ViewerPdfState, any ViewerPdfIntent, KotlinNothing>
typealias ViewerTextHost = ViewModelHost<ViewerTextState, any ViewerTextIntent, any ViewerTextEffect>
typealias ViewerArchiveHost = ViewModelHost<ViewerArchiveState, any ViewerArchiveIntent, any ViewerArchiveEffect>
typealias ViewerWebHost = ViewModelHost<ViewerWebState, any ViewerWebIntent, KotlinNothing>
typealias ArchiveTaskHost = ViewModelHost<ArchiveTaskState, any ArchiveTaskIntent, any ArchiveTaskEffect>

extension ViewModelHost where S == ViewerImageState, I == any ViewerImageIntent {
    /// 当前页（左右滑动切换同目录图片）
    var selectedIndex: Int {
        get { Int(state.index) }
        set { if newValue != Int(state.index) { send(ViewerImageIntentSelect(index: Int32(newValue))) } }
    }
}

extension ViewModelHost where S == ViewerTextState, I == any ViewerTextIntent {
    /// 编辑中的文本
    var draft: String {
        get { state.draft }
        set { if newValue != state.draft { send(ViewerTextIntentEdit(text: newValue)) } }
    }

    /// 40076：文件已在其他地方被修改（关闭对话框 = 放弃本次保存）
    var isStaleVersionPresented: Bool {
        get { state.staleVersion }
        set { if !newValue && state.staleVersion { send(ViewerTextIntentDismissConflict.shared) } }
    }

    /// 40073：文件被锁定
    var isLockConflictPresented: Bool {
        get { !state.lockConflicts.isEmpty }
        set { if !newValue && !state.lockConflicts.isEmpty { send(ViewerTextIntentDismissConflict.shared) } }
    }
}

extension ViewModelHost where S == ArchiveTaskState, I == any ArchiveTaskIntent {
    var password: String {
        get { state.password }
        set { send(ArchiveTaskIntentSetPassword(password: newValue)) }
    }
}
