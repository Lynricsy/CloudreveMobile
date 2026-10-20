import SwiftUI
import OpenCloudreveCore

/// 一个文件列表（或搜索结果）的操作协调者：持有各对话框/sheet 的状态，把菜单动作翻译成
/// `FileActionViewModel` 的操作或界面。由列表视图以 `@State` 持有（构造很轻），向下传给行与菜单。
@MainActor
@Observable
final class FileActionDriver {
    /// 正在重命名的文件与输入框内容
    var renaming: FileItem?
    var renameText = ""
    /// 待确认删除的文件
    var deleting: [FileItem] = []
    /// 新建文件夹 / 新建文件（按预览器模板）
    var creatingFolder = false
    var newFolderName = ""
    var newFileOption: NewFileOption?
    var newFileName = ""
    /// 清空回收站确认
    var confirmingEmptyTrash = false
    /// 当前 sheet
    var sheet: FileSheet?
    /// 后续阶段以全屏呈现的界面
    var fullScreen: FileSheet?
    /// 在当前导航栈推入的目录（“前往所在目录”）或后续阶段界面
    var pushed: FilePushRoute?
    /// 需要提示的错误
    var errorMessage: String?

    var isRenaming: Bool {
        get { renaming != nil }
        set { if !newValue { renaming = nil } }
    }

    var isDeleting: Bool {
        get { !deleting.isEmpty }
        set { if !newValue { deleting = [] } }
    }

    var isCreatingFile: Bool {
        get { newFileOption != nil }
        set { if !newValue { newFileOption = nil } }
    }

    var isErrorPresented: Bool {
        get { errorMessage != nil }
        set { if !newValue { errorMessage = nil } }
    }

    /// 执行菜单动作
    func perform(_ action: FileAction, targets: [FileItem], in context: FileActionContext) {
        guard let first = targets.first else { return }
        let uris = targets.map(\.path)
        if let hook = FileFeatures.installed.hook(for: action) {
            present(hook, context: context.hookContext(files: targets, folderUri: context.listingUri))
            return
        }
        switch action {
        case .rename:
            renameText = first.name
            renaming = first
        case .move, .copy:
            let parent = CrUri.companion.parseOrNull(s: first.path)?.parent()?.description() ?? CrUri.companion.my(uid: nil).description()
            sheet = .folderPicker(FolderPickRequest(
                purpose: action == .move ? .move : .copy,
                sources: uris,
                initialUri: parent,
                excluded: action == .move ? targets.filter(\.isFolder).map(\.path) : []
            ))
        case .delete:
            deleting = targets
        case .restore:
            context.actions.run(FileOperationRestore(uris: uris))
        case .pin:
            if context.isPinned(first.path) {
                context.actions.run(FileOperationUnpin(uri: first.path))
            } else {
                context.actions.run(FileOperationPin(uri: first.path, name: first.name))
            }
        case .info:
            sheet = .info(id: UUID(), file: first)
        case .directLink:
            context.actions.run(FileOperationCreateSourceLinks(uris: uris))
        case .resetThumbnail:
            context.actions.run(FileOperationSetThumbnailDisabled(uris: targets.filter(\.thumbDisabled).map(\.path), disabled: false))
        case .disableThumbnail:
            context.actions.run(FileOperationSetThumbnailDisabled(uris: uris, disabled: true))
        case .goToSharedLink:
            if let link = first.metadata[MetadataKeys.shared.SHARED_REDIRECT] {
                sheet = .shareVisit(id: UUID(), link: link)
            }
        case .goToParent:
            if let parent = CrUri.companion.parseOrNull(s: first.path)?.parent() {
                pushed = .folder(parent.description())
            }
        case .mountPolicy:
            sheet = .mountPolicy(id: UUID(), folder: first)
        case .open, .download, .share, .versions, .metadata, .relocate, .createArchive, .extractArchive, .remoteDownload:
            // 后续阶段动作：未接入时不会出现在菜单中
            break
        }
    }

    /// 点按文件：有预览器入口且可预览时打开，否则显示详细信息
    func open(_ file: FileItem, in context: FileActionContext) {
        if let preview = FileFeatures.installed.preview,
           FileActionPolicy.available(targets: [file], user: context.user, hasViewer: context.hasViewer(file)).contains(.open) {
            present(preview, context: context.hookContext(files: [file], folderUri: context.listingUri))
        } else {
            sheet = .info(id: UUID(), file: file)
        }
    }

    /// 呈现后续阶段的界面
    func present(_ hook: FileHook, context: FileHookContext) {
        guard let view = hook.content(context) else { return }
        switch hook.presentation {
        case .sheet: sheet = .hook(id: UUID(), content: view)
        case .fullScreen: fullScreen = .hook(id: UUID(), content: view)
        case .push: pushed = .hook(id: UUID(), content: view)
        }
    }

    /// 选择器返回目标目录后执行移动/复制
    func finishPick(_ request: FolderPickRequest, destination: String, actions: FileActionHost) {
        switch request.purpose {
        case .move: actions.run(FileOperationMove(uris: request.sources, dst: destination))
        case .copy: actions.run(FileOperationCopy(uris: request.sources, dst: destination))
        }
    }
}
