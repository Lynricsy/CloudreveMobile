import Foundation
import OpenCloudreveCore

/// 动作显示规则，照前端 `useActionDisplayOpt.ts:getActionOpt`（规格 §7.7，与 Android `FileActionPolicy` 一致）：
/// - 可管理 = 本人文件，或管理员在 `my` 文件系统；可更新 = 可管理且不在 `share` 文件系统；
/// - 能力位按“任一目标具备”判断，还原按“全部目标具备”。
enum FileActionPolicy {
    private static let archiveExts: Set<String> = ["zip", "gz", "xz", "tar", "rar", "7z", "bz2"]

    /// - Parameters:
    ///   - hasViewer: 单个文件是否有可用预览器
    ///   - inSearch: 目标来自搜索结果（提供“前往所在目录”）
    static func available(
        targets: [FileItem],
        user: User?,
        hasViewer: Bool = false,
        inSearch: Bool = false
    ) -> Set<FileAction> {
        guard let first = targets.first else { return [] }
        let group = user?.group?.permission
        func groupHas(_ bit: Int32) -> Bool { group?.has(bit: bit) == true }
        let isAdmin = groupHas(GroupPermission.shared.IS_ADMIN)
        let loggedIn = user != nil && user?.anonymous == false
        let listingFs = CrUri.companion.parseOrNull(s: first.path)?.fs
        let fsMy = CrUri.companion.FS_MY

        let manageable = targets.map { canManage($0, isAdmin: isAdmin) }
        let updatable = manageable.map { $0 && listingFs != CrUri.companion.FS_SHARE }
        let allUpdatable = updatable.allSatisfy { $0 }
        let hasUpdatable = updatable.contains(true)
        let hasOwned = manageable.contains(true)
        let hasFile = targets.contains { !$0.isFolder }
        let hasFolder = targets.contains { $0.isFolder }
        let hasTrashFile = targets.contains { $0.metadata[MetadataKeys.shared.RESTORE_URI] != nil }
        let hasFailedThumb = targets.contains { $0.thumbDisabled }
        let withCap = targets.compactMap(\.capability)
        func any(_ bit: Int32) -> Bool { withCap.contains { $0.has(bit: bit) } }
        func all(_ bit: Int32) -> Bool { !withCap.isEmpty && withCap.allSatisfy { $0.has(bit: bit) } }
        let single = targets.count == 1
        let ext = FileFormat.ext(first)
        let cap = NavigatorCapability.shared
        let download = any(cap.DOWNLOAD_FILE)
        let symbolic = first.isSymbolic

        var out = Set<FileAction>()
        if single && hasFile && download && hasViewer { out.insert(.open) }
        if download { out.insert(.download) }
        if single && loggedIn && groupHas(GroupPermission.shared.SHARE) && allUpdatable &&
            (first.owned || isAdmin) && any(cap.SHARE) && !symbolic && !hasTrashFile {
            out.insert(.share)
        }
        if single && allUpdatable && any(cap.RENAME_FILE) { out.insert(.rename) }
        // 服务端只允许“我的文件”之间移动/复制（回收站用还原，分享用保存快捷方式）
        if hasUpdatable && !withCap.isEmpty && listingFs == fsMy {
            out.insert(.move)
            out.insert(.copy)
        }
        if hasUpdatable && (any(cap.SOFT_DELETE) || any(cap.DELETE_FILE)) { out.insert(.delete) }
        if all(cap.RESTORE) { out.insert(.restore) }
        if !hasTrashFile && single && hasFolder && loggedIn { out.insert(.pin) }
        if single && any(cap.INFO) { out.insert(.info) }
        if single && hasFile && !symbolic && any(cap.VERSION_CONTROL) { out.insert(.versions) }
        if hasUpdatable && any(cap.UPDATE_METADATA) { out.insert(.metadata) }
        let directLinkBatch = Int(user?.group?.directLinkBatchSize ?? 0)
        if (hasOwned || isAdmin) && download && directLinkBatch >= targets.count &&
            (hasFile && !hasFolder || single && hasFolder && groupHas(GroupPermission.shared.FOLDER_DIRECT_LINK)) {
            out.insert(.directLink)
        }
        let thumbEditable = hasFile && !hasFolder && allUpdatable && any(cap.UPDATE_METADATA)
        if thumbEditable && hasFailedThumb { out.insert(.resetThumbnail) }
        if thumbEditable && targets.contains(where: { !$0.thumbDisabled }) { out.insert(.disableThumbnail) }
        if single && symbolic { out.insert(.goToSharedLink) }
        if inSearch && single { out.insert(.goToParent) }
        if single && hasFolder && manageable[0] && !hasTrashFile && loggedIn && listingFs == fsMy && !symbolic {
            out.insert(.mountPolicy)
        }
        if allUpdatable && !hasTrashFile && loggedIn && groupHas(GroupPermission.shared.RELOCATE) && listingFs == fsMy {
            out.insert(.relocate)
        }
        if loggedIn && groupHas(GroupPermission.shared.ARCHIVE_TASK) && download { out.insert(.createArchive) }
        if single && hasFile && download && loggedIn && groupHas(GroupPermission.shared.ARCHIVE_TASK) && archiveExts.contains(ext) {
            out.insert(.extractArchive)
        }
        if single && hasFile && download && loggedIn && groupHas(GroupPermission.shared.REMOTE_DOWNLOAD) && ext == "torrent" {
            out.insert(.remoteDownload)
        }
        return out
    }

    /// 本人文件，或管理员在“我的文件”文件系统中（含 `cloudreve://<uid>@my`）
    static func canManage(_ file: FileItem, isAdmin: Bool) -> Bool {
        file.owned || isAdmin && CrUri.companion.parseOrNull(s: file.path)?.fs == CrUri.companion.FS_MY
    }

    /// 删除确认是否提供“仅解除链接”：彻底删除或含回收站项，且用户组有 advance_delete
    static func canUnlink(user: User?, skipSoftDelete: Bool, hasTrashFile: Bool) -> Bool {
        (skipSoftDelete || hasTrashFile) && user?.group?.permission?.has(bit: GroupPermission.shared.ADVANCE_DELETE) == true
    }
}
