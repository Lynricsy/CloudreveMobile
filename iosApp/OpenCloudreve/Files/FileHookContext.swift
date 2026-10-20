import SwiftUI
import OpenCloudreveCore

/// 调用后续阶段动作时的上下文
struct FileHookContext {
    let accountId: String
    /// 触发动作时所在的目录（或搜索）URI
    let listingUri: String
    /// 目标文件（目录动作时为空）
    let files: [FileItem]
    /// 目标目录 URI（上传/新建等目录动作；文件动作时为 `listingUri`）
    let folderUri: String
    /// 当前目录的存储策略（上传预校验用，可能为 nil）
    let storagePolicy: StoragePolicy?
    /// 让发起动作的列表刷新
    let refresh: @MainActor () -> Void
}
