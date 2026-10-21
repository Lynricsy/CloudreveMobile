import OpenCloudreveCore

typealias VersionsHost = ViewModelHost<VersionsState, any VersionsIntent, any VersionsEffect>
typealias MetadataEditHost = ViewModelHost<MetadataEditState, any MetadataEditIntent, any MetadataEditEffect>
typealias DirectLinksHost = ViewModelHost<DirectLinksState, any DirectLinksIntent, any DirectLinksEffect>

// 可写计算属性：读状态、写转成意图，配合 `@Bindable` 得到绑定

extension ViewModelHost where S == DirectLinksState, I == any DirectLinksIntent {
    /// 链接改为强制下载形式（`/f/` → `/f/d/`）
    var forceDownload: Bool {
        get { state.forceDownload }
        set { send(DirectLinksIntentSetForceDownload(enabled: newValue)) }
    }

    /// 复制文本带 `[文件名]` 前缀
    var showFileName: Bool {
        get { state.showFileName }
        set { send(DirectLinksIntentSetShowFileName(enabled: newValue)) }
    }
}
