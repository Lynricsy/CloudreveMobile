import SwiftUI
import OpenCloudreveCore

/// 文件浏览器中由后续阶段实现的动作入口（集中接入点）。
/// 为 nil 的动作**不出现在任何菜单中**；显示与否仍受 `FileActionPolicy` 的权限/能力规则约束。
/// 接入方式与约定见 `local://ios-files-hooks.md`。
struct FileFeatures {
    /// P5：下载到本地（单选/多选）
    var download: FileHook?
    /// P5：上传文件到目录（新建菜单“上传文件”）
    var uploadFiles: FileHook?
    /// P5：上传照片/视频（新建菜单）
    var uploadMedia: FileHook?
    /// P5：拍照上传（新建菜单）
    var uploadCamera: FileHook?
    /// P6：打开预览（单个文件）；为 nil 时点按文件显示详细信息
    var preview: FileHook?
    /// P7：新建分享
    var share: FileHook?
    /// P7：版本管理
    var versions: FileHook?
    /// P7：标签与元数据、图标自定义
    var metadata: FileHook?
    /// P7：压缩
    var createArchive: FileHook?
    /// P7：解压
    var extractArchive: FileHook?
    /// P7：从种子文件创建离线下载
    var remoteDownload: FileHook?
    /// P7：转移存储策略
    var relocate: FileHook?

    /// 当前接入的实现：后续阶段在此填入各自的 Hook
    @MainActor static let installed = FileFeatures()

    /// 动作对应的入口；不是后续阶段动作时为 nil
    func hook(for action: FileAction) -> FileHook? {
        switch action {
        case .open: preview
        case .download: download
        case .share: share
        case .versions: versions
        case .metadata: metadata
        case .createArchive: createArchive
        case .extractArchive: extractArchive
        case .remoteDownload: remoteDownload
        case .relocate: relocate
        default: nil
        }
    }

    /// 动作是否需要后续阶段接入（未接入时从菜单中隐藏）
    static func isHooked(_ action: FileAction) -> Bool {
        switch action {
        case .open, .download, .share, .versions, .metadata, .createArchive, .extractArchive, .remoteDownload, .relocate: true
        default: false
        }
    }

    /// 去掉未接入的后续阶段动作
    func filter(_ actions: Set<FileAction>) -> Set<FileAction> {
        actions.filter { !Self.isHooked($0) || hook(for: $0) != nil }
    }
}
