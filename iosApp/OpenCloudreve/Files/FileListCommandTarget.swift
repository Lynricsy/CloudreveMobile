import SwiftUI
import OpenCloudreveCore

/// 当前可见目录列表对键盘命令（⌘N 新建文件夹、⌘R 刷新、Delete 删除选中）暴露的操作。
/// 由 `FileListContent` 在可见时以 `focusedSceneValue` 发布，`AppCommands` 读取。
@MainActor
struct FileListCommandTarget {
    let list: FileListHost
    let driver: FileActionDriver
    let context: FileActionContext

    var canCreateFolder: Bool {
        list.state.canCreate && !list.state.selecting
    }

    /// 可删除的已选条目；不在多选模式、未选择或其中有不可删除的条目时为空
    var deletableSelection: [FileItem] {
        let state = list.state
        guard state.selecting, !state.selectedFiles.isEmpty else { return [] }
        let maxBatch = Int(state.maxBatchSize)
        if maxBatch > 0 && state.selectedFiles.count > maxBatch { return [] }
        return context.visibleActions(for: state.selectedFiles).contains(.delete) ? state.selectedFiles : []
    }

    func newFolder() {
        guard canCreateFolder else { return }
        driver.newFolderName = String(localized: "新建文件夹")
        driver.creatingFolder = true
    }

    func refresh() {
        list.send(FileListIntentRefresh.shared)
    }

    func deleteSelection() {
        let targets = deletableSelection
        guard !targets.isEmpty else { return }
        driver.perform(.delete, targets: targets, in: context)
    }
}
