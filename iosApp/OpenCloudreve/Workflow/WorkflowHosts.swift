import OpenCloudreveCore

// 后台任务 VM 宿主类型与绑定（约定见 local://ios-conventions.md「ViewModelHost 用法」）
// `ArchiveTaskHost` 定义在 Viewer/ViewerHosts.swift（解压表单共用）

typealias WorkflowListHost = ViewModelHost<WorkflowListState, any WorkflowListIntent, any WorkflowListEffect>
typealias RemoteDownloadCreateHost = ViewModelHost<RemoteDownloadCreateState, any RemoteDownloadCreateIntent, any RemoteDownloadCreateEffect>

extension ViewModelHost where S == WorkflowListState, I == any WorkflowListIntent {
    /// 当前分类（`general` / `downloading` / `downloaded`）
    var category: String {
        get { state.category }
        set { if newValue != state.category { send(WorkflowListIntentSetCategory(category: newValue)) } }
    }

    /// 详情页是否推入（返回时关闭详情、停止进度轮询）
    var isDetailPresented: Bool {
        get { state.detail != nil }
        set { if !newValue && state.detail != nil { send(WorkflowListIntentCloseDetail.shared) } }
    }
}

extension ViewModelHost where S == RemoteDownloadCreateState, I == any RemoteDownloadCreateIntent {
    /// 多行下载链接
    var urlsText: String {
        get { state.urlsText }
        set { send(RemoteDownloadCreateIntentSetUrls(text: newValue)) }
    }
}

extension ViewModelHost where S == ArchiveTaskState, I == any ArchiveTaskIntent {
    /// 压缩包文件名
    var archiveName: String {
        get { state.archiveName }
        set { send(ArchiveTaskIntentSetArchiveName(name: newValue)) }
    }
}
