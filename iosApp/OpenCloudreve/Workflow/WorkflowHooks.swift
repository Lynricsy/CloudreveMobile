import SwiftUI
import OpenCloudreveCore

/// 文件浏览器的 P7 后台任务动作（接入 `FileFeatures.installed`）与任务 Tab 的后台任务列表（`TasksFeatures.installed.backgroundTasks`）
@MainActor
enum WorkflowHooks {
    /// 压缩到当前目录（搜索结果里为首个文件的所在目录）；单个文件默认以其名称命名压缩包
    static let createArchive = FileHook(presentation: .sheet) { ctx in
        guard let first = ctx.files.first else { return nil }
        let dstUri: String
        if CrUri.companion.parseOrNull(s: ctx.folderUri)?.isSearch() == true {
            dstUri = WorkflowHooks.parentUri(of: first, fallback: CrUri.companion.my(uid: nil).description())
        } else {
            dstUri = ctx.folderUri
        }
        return AnyView(ArchiveCreateSheet(
            accountId: ctx.accountId,
            srcUris: ctx.files.map(\.linkedUri),
            dstUri: dstUri,
            initialName: ctx.files.count == 1 ? WorkflowHooks.archiveName(for: first) : nil,
            onCreated: ctx.refresh
        ))
    }

    /// 解压到压缩包所在目录（可改）
    static let extractArchive = FileHook(presentation: .sheet) { ctx in
        guard let file = ctx.files.first else { return nil }
        return AnyView(ArchiveExtractSheet(
            accountId: ctx.accountId,
            request: ArchiveExtractRequest(
                fileUri: file.linkedUri,
                encoding: nil,
                mask: [],
                parentUri: WorkflowHooks.parentUri(of: file, fallback: ctx.folderUri)
            )
        ))
    }

    /// 用种子文件创建离线下载，默认保存到种子所在目录
    static let remoteDownload = FileHook(presentation: .sheet) { ctx in
        guard let file = ctx.files.first else { return nil }
        return AnyView(RemoteDownloadSheet(
            accountId: ctx.accountId,
            dstUri: WorkflowHooks.parentUri(of: file, fallback: ctx.folderUri),
            torrentUri: file.path,
            onCreated: ctx.refresh
        ))
    }

    /// 转移存储策略
    static let relocate = FileHook(presentation: .sheet) { ctx in
        guard !ctx.files.isEmpty else { return nil }
        return AnyView(RelocateSheet(
            accountId: ctx.accountId,
            srcUris: ctx.files.map(\.linkedUri),
            onCreated: ctx.refresh
        ))
    }

    /// 任务 Tab 的“后台任务”段（不自带导航栈）
    static func backgroundTasks(_ accountId: String) -> AnyView {
        AnyView(WorkflowTasksScreen(accountId: accountId))
    }

    /// 文件的所在目录；无法解析时用 `fallback`
    private static func parentUri(of file: FileItem, fallback: String) -> String {
        CrUri.companion.parseOrNull(s: file.path)?.parent()?.description() ?? fallback
    }

    /// 单个文件压缩时的默认压缩包名：目录用全名，文件去掉扩展名
    private static func archiveName(for file: FileItem) -> String {
        let base = file.isFolder ? file.name : (file.name as NSString).deletingPathExtension
        return (base.isEmpty ? file.name : base) + ".zip"
    }
}
