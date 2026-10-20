import SwiftUI

/// 文件浏览器中的传输入口（接入 `FileFeatures.installed`）
@MainActor
enum TransferHooks {
    /// 下载（单选/多选）：直接加入下载队列，进度见底部迷你栏与任务 Tab
    static let download = FileHook(presentation: .sheet) { context in
        DownloadEnqueuer.enqueue(accountId: context.accountId, files: context.files)
        return nil
    }

    static let uploadFiles = upload(.files)
    static let uploadMedia = upload(.media)
    static let uploadCamera = upload(.camera)

    private static func upload(_ mode: UploadMode) -> FileHook {
        FileHook(presentation: .sheet) { context in
            AnyView(UploadSheet(
                mode: mode,
                accountId: context.accountId,
                targetUri: context.folderUri,
                policy: context.storagePolicy,
                onDone: context.refresh
            ))
        }
    }
}
