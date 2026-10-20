import SwiftUI
import OpenCloudreveCore

/// 挂在文件列表/搜索结果上的操作界面：输入/确认对话框、sheet、锁冲突与部分失败提示，
/// 并收集 `FileActionViewModel` 的事件（成功时回调 `onChanged` 让列表刷新）。
struct FileActionsPresenter: ViewModifier {
    @Bindable var driver: FileActionDriver
    @Bindable var actions: FileActionHost
    let context: FileActionContext
    let onChanged: (FileOperation, FileOperationOutcome) -> Void

    func body(content: Content) -> some View {
        content
            .sheet(item: $driver.sheet) { sheet in
                sheetContent(sheet)
            }
            .fullScreenCover(item: $driver.fullScreen) { sheet in
                sheetContent(sheet)
            }
            .navigationDestination(item: $driver.pushed) { route in
                switch route {
                case let .folder(uri): FileListScreen(accountId: context.accountId, uri: uri)
                case let .hook(_, view): view
                }
            }
            .alert("重命名", isPresented: $driver.isRenaming, presenting: driver.renaming) { file in
                TextField("名称", text: $driver.renameText)
                    .accessibilityIdentifier("files.rename.field")
                Button("取消", role: .cancel) {}
                Button("确定") {
                    let name = driver.renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !name.isEmpty, name != file.name {
                        actions.run(FileOperationRename(uri: file.path, newName: name))
                    }
                }
                .accessibilityIdentifier("files.rename.confirm")
            }
            .alert("新建文件夹", isPresented: $driver.creatingFolder) {
                TextField("名称", text: $driver.newFolderName)
                    .accessibilityIdentifier("files.newFolder.field")
                Button("取消", role: .cancel) {}
                Button("创建") {
                    let name = driver.newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !name.isEmpty {
                        actions.run(FileOperationCreateFolder(parentUri: context.listingUri, name: name))
                    }
                }
            }
            .alert("新建文件", isPresented: $driver.isCreatingFile, presenting: driver.newFileOption) { _ in
                TextField("名称", text: $driver.newFileName)
                Button("取消", role: .cancel) {}
                Button("创建") {
                    let name = driver.newFileName.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !name.isEmpty {
                        actions.run(FileOperationCreateFile(parentUri: context.listingUri, name: name))
                    }
                }
            }
            .confirmationDialog(
                deleteTitle,
                isPresented: $driver.isDeleting,
                titleVisibility: .visible,
                presenting: driver.deleting
            ) { files in
                deleteButtons(files)
            }
            .confirmationDialog("清空回收站？", isPresented: $driver.confirmingEmptyTrash, titleVisibility: .visible) {
                Button("清空回收站", role: .destructive) {
                    actions.run(FileOperationEmptyTrash.shared)
                }
            } message: {
                Text("回收站中的所有项目将被永久删除，无法恢复。")
            }
            .alert("操作失败", isPresented: $driver.isErrorPresented, presenting: driver.errorMessage) { _ in
                Button("好", role: .cancel) {}
            } message: { message in
                Text(message)
            }
            .alert("文件已被锁定", isPresented: $actions.isLockConflictPresented, presenting: actions.state.lockConflict) { _ in
                Button("强制解锁并重试", role: .destructive) {
                    actions.send(FileActionIntentForceUnlockAndRetry.shared)
                }
                Button("取消", role: .cancel) {
                    actions.send(FileActionIntentDismissConflict.shared)
                }
            } message: { prompt in
                Text(lockMessage(prompt))
            }
            .alert("部分操作未能完成", isPresented: $actions.isFailuresPresented) {
                Button("好", role: .cancel) {
                    actions.send(FileActionIntentDismissFailures.shared)
                }
            } message: {
                Text(verbatim: failuresMessage(actions.state.partialFailures))
            }
            .task {
                await actions.effects { effect in
                    switch onEnum(of: effect) {
                    case let .succeeded(success):
                        onChanged(success.operation, success.outcome)
                        FileProviderSignal.workingSetChanged(accountId: context.accountId)
                        if case let .sourceLinks(result) = onEnum(of: success.outcome) {
                            driver.sheet = .sourceLinks(id: UUID(), links: result.links, failures: result.failures)
                        }
                    case let .failed(failure):
                        // 部分失败由专门的弹窗列出明细
                        if actions.state.partialFailures.isEmpty {
                            driver.errorMessage = ErrorText.message(failure.error)
                        }
                    }
                }
            }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: FileSheet) -> some View {
        switch sheet {
        case let .folderPicker(request):
            FolderPickerSheet(
                accountId: context.accountId,
                initialUri: request.initialUri,
                title: request.purpose == .move ? "移动到" : "复制到",
                excludedUris: request.excluded
            ) { destination in
                driver.finishPick(request, destination: destination, actions: actions)
            }
        case let .mountPolicy(_, folder):
            MountPolicySheet(accountId: context.accountId, folder: folder) { policyId in
                actions.run(FileOperationMountPolicy(uri: folder.path, policyId: policyId))
            }
        case let .info(_, file):
            FileInfoSheet(accountId: context.accountId, file: file)
        case let .sourceLinks(_, links, failures):
            SourceLinksSheet(links: links, failures: failures)
        case let .shareVisit(_, link):
            ShareVisitSheet(accountId: context.accountId, link: link)
        case let .hook(_, view):
            view
        }
    }

    private var deleteTitle: LocalizedStringKey {
        "删除 \(driver.deleting.count) 个项目？"
    }

    /// 删除选项：回收站内只能彻底删除；有 advance_delete 权限时可“仅解除链接”（保留物理文件）
    @ViewBuilder
    private func deleteButtons(_ files: [FileItem]) -> some View {
        let uris = files.map(\.path)
        let inTrash = CrUri.companion.parseOrNull(s: files.first?.path ?? "")?.fs == CrUri.companion.FS_TRASH
        let hasTrashFile = files.contains { $0.metadata[MetadataKeys.shared.RESTORE_URI] != nil }
        let canSoftDelete = !inTrash && files.contains { $0.capability?.has(bit: NavigatorCapability.shared.SOFT_DELETE) == true }
        if canSoftDelete {
            Button("移到回收站", role: .destructive) {
                actions.run(FileOperationDelete(uris: uris, unlink: false, skipSoftDelete: false))
            }
            .accessibilityIdentifier("files.delete.trash")
        }
        Button("彻底删除", role: .destructive) {
            actions.run(FileOperationDelete(uris: uris, unlink: false, skipSoftDelete: true))
        }
        .accessibilityIdentifier("files.delete.permanent")
        if FileActionPolicy.canUnlink(user: context.user, skipSoftDelete: true, hasTrashFile: hasTrashFile) {
            Button("仅解除链接（保留存储中的文件）", role: .destructive) {
                actions.run(FileOperationDelete(uris: uris, unlink: true, skipSoftDelete: true))
            }
        }
    }

    private func lockMessage(_ prompt: LockConflictPrompt) -> String {
        let lines = prompt.conflicts.prefix(10).map { conflict -> String in
            let path = conflict.path.map { raw in
                CrUri.companion.parseOrNull(s: raw).map(FileFormat.displayPath) ?? raw
            } ?? "—"
            return "\(path)（\(lockOwner(conflict.owner?.application?.type))）"
        }
        let header = String(localized: "以下项目正被其他操作占用。强制解锁可能导致对方的修改丢失。")
        return lines.isEmpty ? header : header + "\n\n" + lines.joined(separator: "\n")
    }

    /// 锁占用方：服务端 `fs.ApplicationType`（WebDAV 锁为 `dav`），与网页端 LockConflictDetails 的映射一致
    private func lockOwner(_ type: String?) -> String {
        switch type {
        case "dav": "WebDAV"
        case "viewer": String(localized: "在线编辑")
        case "rename": String(localized: "重命名")
        case "moveCopy": String(localized: "移动/复制")
        case "upload": String(localized: "上传")
        case "updateMetadata": String(localized: "修改元数据")
        case "delete", "softDelete": String(localized: "删除")
        case "versionControl": String(localized: "版本管理")
        case nil, "": String(localized: "未知操作")
        case let other?: other
        }
    }

    private func failuresMessage(_ failures: [OperationFailure]) -> String {
        failures.prefix(10).map { failure in
            let name = CrUri.companion.parseOrNull(s: failure.uri)?.name() ?? failure.uri
            return "\(name)：\(ErrorText.message(failure.error))"
        }
        .joined(separator: "\n")
    }
}

extension View {
    /// 挂载文件操作界面（见 `FileActionsPresenter`）
    func fileActions(
        driver: FileActionDriver,
        actions: FileActionHost,
        context: FileActionContext,
        onChanged: @escaping (FileOperation, FileOperationOutcome) -> Void
    ) -> some View {
        modifier(FileActionsPresenter(driver: driver, actions: actions, context: context, onChanged: onChanged))
    }
}
