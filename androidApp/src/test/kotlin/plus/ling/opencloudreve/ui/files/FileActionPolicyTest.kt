package plus.ling.opencloudreve.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileType
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.model.UserGroup

class FileActionPolicyTest {
    private fun caps(vararg bits: Int): BoolSet = bits.fold(BoolSet()) { acc, b -> acc.with(b) }

    /** “我的文件”文件系统的能力集（规格 §0） */
    private val myCaps = caps(
        NavigatorCapability.CREATE_FILE, NavigatorCapability.RENAME_FILE, NavigatorCapability.UPLOAD_FILE,
        NavigatorCapability.DOWNLOAD_FILE, NavigatorCapability.UPDATE_METADATA, NavigatorCapability.LIST_CHILDREN,
        NavigatorCapability.GENERATE_THUMB, NavigatorCapability.DELETE_FILE, NavigatorCapability.LOCK_FILE,
        NavigatorCapability.SOFT_DELETE, NavigatorCapability.SHARE, NavigatorCapability.INFO,
        NavigatorCapability.VERSION_CONTROL, NavigatorCapability.ENTER_FOLDER, NavigatorCapability.MODIFY_PROPS,
    )
    private val trashCaps = caps(
        NavigatorCapability.LIST_CHILDREN, NavigatorCapability.DELETE_FILE, NavigatorCapability.LOCK_FILE,
        NavigatorCapability.RESTORE, NavigatorCapability.INFO,
    )
    private val shareCaps = caps(
        NavigatorCapability.DOWNLOAD_FILE, NavigatorCapability.LIST_CHILDREN, NavigatorCapability.GENERATE_THUMB,
        NavigatorCapability.LOCK_FILE, NavigatorCapability.INFO, NavigatorCapability.VERSION_CONTROL,
        NavigatorCapability.ENTER_FOLDER, NavigatorCapability.MODIFY_PROPS,
    )

    private fun user(vararg perms: Int, directLinkBatch: Int = 10) =
        User(id = "u1", group = UserGroup(id = "g", permission = caps(*perms), directLinkBatchSize = directLinkBatch))

    private fun file(path: String, folder: Boolean = false, cap: BoolSet = myCaps, owned: Boolean = true, metadata: Map<String, String> = emptyMap()) =
        FileItem(
            type = if (folder) FileType.FOLDER else FileType.FILE,
            name = path.substringAfterLast('/'),
            path = path,
            capability = cap,
            owned = owned,
            metadata = metadata,
        )

    @Test
    fun ownFileInMyFilesoffersManagementActions() {
        val actions = FileActionPolicy.available(listOf(file("cloudreve://my/a.txt")), user(GroupPermission.SHARE))
        for (a in listOf(FileAction.Rename, FileAction.Move, FileAction.Copy, FileAction.Delete, FileAction.Info, FileAction.Download, FileAction.Share, FileAction.DirectLink)) {
            assertTrue("$a", a in actions)
        }
        assertFalse(FileAction.Restore in actions)
        assertFalse(FileAction.Pin in actions) // 只能固定目录
    }

    @Test
    fun singleFolderCanBePinnedAndMountedButMultiSelectionCannotRename() {
        val folder = file("cloudreve://my/dir", folder = true)
        val single = FileActionPolicy.available(listOf(folder), user())
        assertTrue(FileAction.Pin in single)
        assertTrue(FileAction.MountPolicy in single)
        assertTrue(FileAction.Enter in single)
        val multi = FileActionPolicy.available(listOf(folder, file("cloudreve://my/b.txt")), user())
        assertFalse(FileAction.Rename in multi)
        assertFalse(FileAction.Pin in multi)
        assertTrue(FileAction.Move in multi)
    }

    @Test
    fun trashItemsOfferRestoreAndPermanentDeleteOnly() {
        val item = file("cloudreve://trash/a.txt", cap = trashCaps, metadata = mapOf(MetadataKeys.RESTORE_URI to "cloudreve://my/a.txt"))
        val actions = FileActionPolicy.available(listOf(item), user())
        assertTrue(FileAction.Restore in actions)
        assertTrue(FileAction.Delete in actions)
        assertFalse(FileAction.Rename in actions)
        assertFalse(FileAction.Share in actions)
        assertFalse(FileAction.Move in actions)
        assertFalse(FileAction.Pin in actions)
    }

    @Test
    fun shareFilesystemIsReadOnly() {
        val item = file("cloudreve://abc@share/a.txt", cap = shareCaps, owned = false)
        val actions = FileActionPolicy.available(listOf(item), user(GroupPermission.SHARE))
        assertFalse(FileAction.Rename in actions)
        assertFalse(FileAction.Delete in actions)
        assertFalse(FileAction.Move in actions)
        assertTrue(FileAction.Download in actions)
        assertTrue(FileAction.Info in actions)
    }

    @Test
    fun symbolicFileHidesShareAndOffersGoToSharedLink() {
        val link = file("cloudreve://my/link", metadata = mapOf(MetadataKeys.SHARED_REDIRECT to "cloudreve://abc@share/x"))
        val actions = FileActionPolicy.available(listOf(link), user(GroupPermission.SHARE))
        assertFalse(FileAction.Share in actions)
        assertFalse(FileAction.Versions in actions)
        assertTrue(FileAction.GoToSharedLink in actions)
    }

    @Test
    fun directLinkRespectsBatchSizeAndFolderPermission() {
        val files = listOf(file("cloudreve://my/a"), file("cloudreve://my/b"))
        assertFalse(FileAction.DirectLink in FileActionPolicy.available(files, user(directLinkBatch = 1)))
        val folder = file("cloudreve://my/d", folder = true)
        assertFalse(FileAction.DirectLink in FileActionPolicy.available(listOf(folder), user()))
        assertTrue(FileAction.DirectLink in FileActionPolicy.available(listOf(folder), user(GroupPermission.FOLDER_DIRECT_LINK)))
    }

    @Test
    fun thumbnailToggleDependsOnCurrentState() {
        val disabled = file("cloudreve://my/a.jpg", metadata = mapOf(MetadataKeys.THUMB_DISABLED to ""))
        val actions = FileActionPolicy.available(listOf(disabled), user())
        assertTrue(FileAction.ResetThumbnail in actions)
        assertFalse(FileAction.DisableThumbnail in actions)
        assertEquals(
            setOf(FileAction.DisableThumbnail),
            FileActionPolicy.available(listOf(file("cloudreve://my/b.jpg")), user())
                .intersect(setOf(FileAction.ResetThumbnail, FileAction.DisableThumbnail)),
        )
    }

    @Test
    fun adminManagesOthersFilesOnlyInMyFilesystem() {
        val other = file("cloudreve://uid2@my/a.txt", owned = false)
        assertTrue(FileAction.Rename in FileActionPolicy.available(listOf(other), user(GroupPermission.IS_ADMIN)))
        assertFalse(FileAction.Rename in FileActionPolicy.available(listOf(other), user()))
    }

    @Test
    fun unlinkRequiresAdvanceDeleteAndPermanentOrTrash() {
        assertFalse(FileActionPolicy.canUnlink(user(), skipSoftDelete = true, hasTrashFile = false))
        assertFalse(FileActionPolicy.canUnlink(user(GroupPermission.ADVANCE_DELETE), skipSoftDelete = false, hasTrashFile = false))
        assertTrue(FileActionPolicy.canUnlink(user(GroupPermission.ADVANCE_DELETE), skipSoftDelete = true, hasTrashFile = false))
        assertTrue(FileActionPolicy.canUnlink(user(GroupPermission.ADVANCE_DELETE), skipSoftDelete = false, hasTrashFile = true))
    }

    @Test
    fun hookBackedActionsHiddenUntilInstalled() {
        val f = file("cloudreve://my/a.txt")
        val none = visibleActions(listOf(f), user(GroupPermission.SHARE), null, false, FileFeatures())
        assertFalse(FileAction.Download in none)
        assertFalse(FileAction.Share in none)
        assertTrue(FileAction.Rename in none)
        val withShare = visibleActions(listOf(f), user(GroupPermission.SHARE), null, false, FileFeatures(share = { _, _ -> }))
        assertTrue(FileAction.Share in withShare)
    }
}
