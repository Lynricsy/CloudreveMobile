import SwiftUI
import OpenCloudreveCore

/// 当前可见目录列表对键盘菜单命令（`AppCommands`：⌘N 新建文件夹、⌘R 刷新、⌘⌫ 删除所选）暴露的操作。
/// 由 `FileListContent` 以 `focusedSceneValue` 发布（页面不可见、被推入的子目录盖住时发布 nil）。
/// 菜单项只按“有没有可见列表”启用：同一列表的状态变化（加载完成、进入多选、选中条目）不会让菜单项重新校验，
/// 所以各操作在执行时按最新状态判断，不满足条件时不做任何事。
@MainActor
struct FileListCommandTarget {
    let list: FileListHost
    let driver: FileActionDriver
    let context: FileActionContext

    /// 多选中且所选条目都可删除（未超过批量上限）
    var canDeleteSelection: Bool {
        let state = list.state
        guard state.selecting, !state.selectedFiles.isEmpty else { return false }
        let maxBatch = Int(state.maxBatchSize)
        if maxBatch > 0 && state.selectedFiles.count > maxBatch { return false }
        return context.visibleActions(for: state.selectedFiles).contains(.delete)
    }

    func newFolder() {
        let state = list.state
        guard state.canCreate && !state.selecting else { return }
        driver.newFolderName = String(localized: "新建文件夹")
        driver.creatingFolder = true
    }

    func refresh() {
        list.send(FileListIntentRefresh.shared)
    }

    /// 删除多选中的条目（弹出与底栏“删除”相同的确认）
    func deleteSelection() {
        guard canDeleteSelection else { return }
        driver.perform(.delete, targets: list.state.selectedFiles, in: context)
    }
}
