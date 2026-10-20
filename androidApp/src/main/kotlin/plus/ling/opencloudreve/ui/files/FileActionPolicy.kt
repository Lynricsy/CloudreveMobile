package plus.ling.opencloudreve.ui.files

import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.User

/** 文件菜单中的动作（含由后续阶段实现的入口，是否显示还取决于 [FileFeatures] 是否接入） */
enum class FileAction {
    Enter,
    Open,
    Download,
    Share,
    Rename,
    Move,
    Copy,
    Delete,
    Restore,
    Pin,
    Info,
    Versions,
    Metadata,
    DirectLink,
    ResetThumbnail,
    DisableThumbnail,
    GoToSharedLink,
    GoToParent,
    MountPolicy,
    Relocate,
    CreateArchive,
    ExtractArchive,
    RemoteDownload,
}

/**
 * 动作显示规则，照前端 `useActionDisplayOpt.ts:getActionOpt`（规格 §7.7）：
 * - 可管理 = 本人文件，或管理员在 `my` 文件系统；可更新 = 可管理且不在 `share` 文件系统；
 * - 能力位按“任一目标具备”（orCapability）判断，还原按“全部目标具备”（andCapability）。
 */
object FileActionPolicy {
    private val archiveExts = setOf("zip", "gz", "xz", "tar", "rar", "7z", "bz2")

    /**
     * @param hasViewer 单个文件是否有可用预览器（打开需要下载能力 + 预览器）
     * @param inSearch 目标来自搜索结果（提供“前往所在目录”）
     */
    fun available(
        targets: List<FileItem>,
        user: User?,
        hasViewer: Boolean = false,
        inSearch: Boolean = false,
    ): Set<FileAction> {
        if (targets.isEmpty()) return emptySet()
        val group = user?.group?.permission
        val isAdmin = group.has(GroupPermission.IS_ADMIN)
        val loggedIn = user != null && !user.anonymous
        val listingFs = CrUri.parseOrNull(targets.first().path)?.fs

        val manageable = targets.map { canManage(it, isAdmin) }
        val updatable = targets.mapIndexed { i, _ -> manageable[i] && listingFs != CrUri.FS_SHARE }
        val allUpdatable = updatable.all { it }
        val hasUpdatable = updatable.any { it }
        val hasOwned = manageable.any { it }
        val hasFile = targets.any { !it.isFolder }
        val hasFolder = targets.any { it.isFolder }
        val hasTrashFile = targets.any { MetadataKeys.RESTORE_URI in it.metadata }
        val hasFailedThumb = targets.any { it.thumbDisabled }
        val withCap = targets.filter { it.capability != null }
        fun or(bit: Int) = withCap.any { it.capability.has(bit) }
        fun and(bit: Int) = withCap.isNotEmpty() && withCap.all { it.capability.has(bit) }
        val single = targets.size == 1
        val first = targets.first()
        val ext = first.extension
        val download = or(NavigatorCapability.DOWNLOAD_FILE)
        val symbolic = first.isSymbolic

        val out = LinkedHashSet<FileAction>()
        if (single && hasFolder && or(NavigatorCapability.ENTER_FOLDER) && !symbolic) out += FileAction.Enter
        if (single && hasFile && download && hasViewer) out += FileAction.Open
        if (download) out += FileAction.Download
        if (single && loggedIn && group.has(GroupPermission.SHARE) && allUpdatable &&
            (first.owned || isAdmin) && or(NavigatorCapability.SHARE) &&
            !symbolic && !hasTrashFile
        ) {
            out += FileAction.Share
        }
        if (single && allUpdatable && or(NavigatorCapability.RENAME_FILE)) out += FileAction.Rename
        // 服务端只允许“我的文件”之间移动/复制（回收站用还原，分享用保存快捷方式）
        if (hasUpdatable && withCap.isNotEmpty() && listingFs == CrUri.FS_MY) {
            out += FileAction.Move
            out += FileAction.Copy
        }
        if (hasUpdatable && (or(NavigatorCapability.SOFT_DELETE) || or(NavigatorCapability.DELETE_FILE))) {
            out += FileAction.Delete
        }
        if (and(NavigatorCapability.RESTORE)) out += FileAction.Restore
        if (!hasTrashFile && single && hasFolder && loggedIn) out += FileAction.Pin
        if (single && or(NavigatorCapability.INFO)) out += FileAction.Info
        if (single && hasFile && !symbolic && or(NavigatorCapability.VERSION_CONTROL)) out += FileAction.Versions
        if (hasUpdatable && or(NavigatorCapability.UPDATE_METADATA)) out += FileAction.Metadata
        val directLinkBatch = user?.group?.directLinkBatchSize ?: 0
        if ((hasOwned || isAdmin) && download && directLinkBatch >= targets.size &&
            (hasFile && !hasFolder || single && hasFolder && group.has(GroupPermission.FOLDER_DIRECT_LINK))
        ) {
            out += FileAction.DirectLink
        }
        val thumbEditable = hasFile && !hasFolder && allUpdatable && or(NavigatorCapability.UPDATE_METADATA)
        if (thumbEditable && hasFailedThumb) out += FileAction.ResetThumbnail
        if (thumbEditable && targets.any { !it.thumbDisabled }) out += FileAction.DisableThumbnail
        if (single && hasFile && symbolic) out += FileAction.GoToSharedLink
        if (inSearch && single) out += FileAction.GoToParent
        if (single && hasFolder && manageable.first() && !hasTrashFile && loggedIn &&
            listingFs == CrUri.FS_MY && !symbolic
        ) {
            out += FileAction.MountPolicy
        }
        if (allUpdatable && !hasTrashFile && loggedIn && group.has(GroupPermission.RELOCATE) && listingFs == CrUri.FS_MY) {
            out += FileAction.Relocate
        }
        if (loggedIn && group.has(GroupPermission.ARCHIVE_TASK) && download) out += FileAction.CreateArchive
        if (single && hasFile && download && loggedIn && group.has(GroupPermission.ARCHIVE_TASK) && ext in archiveExts) {
            out += FileAction.ExtractArchive
        }
        if (single && hasFile && download && loggedIn && group.has(GroupPermission.REMOTE_DOWNLOAD) && ext == "torrent") {
            out += FileAction.RemoteDownload
        }
        return out
    }

    /** 本人文件，或管理员在“我的文件”文件系统中（含 `cloudreve://<uid>@my`） */
    fun canManage(file: FileItem, isAdmin: Boolean): Boolean =
        file.owned || isAdmin && CrUri.parseOrNull(file.path)?.fs == CrUri.FS_MY

    /** 删除对话框是否提供“仅解除链接”：彻底删除或含回收站项，且用户组有 advance_delete */
    fun canUnlink(user: User?, skipSoftDelete: Boolean, hasTrashFile: Boolean): Boolean =
        (skipSoftDelete || hasTrashFile) && user?.group?.permission.has(GroupPermission.ADVANCE_DELETE)

    private fun BoolSet?.has(bit: Int): Boolean = this?.has(bit) == true
}
