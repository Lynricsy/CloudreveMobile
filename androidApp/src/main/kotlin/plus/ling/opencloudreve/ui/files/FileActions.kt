package plus.ling.opencloudreve.ui.files

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.files.NewFileOption
import plus.ling.opencloudreve.core.files.ViewerResolver
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.files.FileActionEffect
import plus.ling.opencloudreve.core.presentation.files.FileActionIntent
import plus.ling.opencloudreve.core.presentation.files.FileActionViewModel
import plus.ling.opencloudreve.core.presentation.files.FileOperation
import plus.ling.opencloudreve.core.presentation.files.FileOperationOutcome
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/** 页面向操作宿主发出的请求 */
sealed interface FileActionRequest {
    data class ShowSheet(val targets: List<FileItem>) : FileActionRequest
    data class Perform(val action: FileAction, val targets: List<FileItem>) : FileActionRequest
    data class NewFolder(val parentUri: String) : FileActionRequest
    data class NewFile(val parentUri: String, val option: NewFileOption) : FileActionRequest
    data object EmptyTrash : FileActionRequest
    data class PickResult(val result: FolderPickResult) : FileActionRequest
}

/** 操作宿主的对话框 */
internal sealed interface FileDialog {
    data class Rename(val file: FileItem) : FileDialog
    data class Delete(val files: List<FileItem>, val inTrash: Boolean) : FileDialog
    data class NewFolder(val parentUri: String) : FileDialog
    data class NewFile(val parentUri: String, val option: NewFileOption) : FileDialog
    data object EmptyTrash : FileDialog
    data class SourceLinks(val outcome: FileOperationOutcome.SourceLinks) : FileDialog
    data class MountPolicy(val folder: FileItem) : FileDialog
}

/**
 * 文件操作的页面侧句柄：页面调用 [request]，[FileActionsHost] 负责菜单、对话框与执行。
 */
@Stable
class FileActionsController {
    internal var sheetTargets by mutableStateOf<List<FileItem>>(emptyList())
    internal var dialog by mutableStateOf<FileDialog?>(null)
    internal var handler: ((FileActionRequest) -> Unit)? = null

    /** 是否有操作正在执行 */
    var busy by mutableStateOf(false)
        internal set

    fun request(request: FileActionRequest) {
        handler?.invoke(request)
    }
}

@Composable
fun rememberFileActionsController(): FileActionsController = remember { FileActionsController() }

/**
 * 文件操作宿主：持有 [FileActionViewModel]（作用域为当前导航目的地），
 * 显示操作菜单、确认/输入对话框、锁冲突与部分失败对话框，并把结果反馈到 Snackbar。
 *
 * @param onChanged 操作成功后调用（列表据此刷新或本地移除）
 */
@Composable
fun FileActionsHost(
    controller: FileActionsController,
    accountId: String,
    listingUri: String,
    user: User?,
    viewerResolver: ViewerResolver?,
    inSearch: Boolean,
    navigator: FileNavigator,
    navController: NavHostController,
    snackbar: SnackbarHostState,
    onChanged: (FileOperation, FileOperationOutcome) -> Unit,
) {
    val vm: FileActionViewModel = koinViewModel(key = "file-actions") { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val features = LocalFileFeatures.current
    val changed by rememberUpdatedState(onChanged)

    val hookContext = remember(accountId, listingUri, navController, navigator) {
        FileHookContext(accountId, listingUri, navController, navigator)
    }

    fun run(op: FileOperation) = vm.on(FileActionIntent.Run(op))

    fun perform(action: FileAction, targets: List<FileItem>) {
        val first = targets.first()
        val uris = targets.map { it.path }
        when (action) {
            FileAction.Enter -> navigator.openFolder(first.path)
            FileAction.Open -> features.preview?.run(hookContext, targets)
            FileAction.Download -> features.download?.run(hookContext, targets)
            FileAction.Share -> features.share?.run(hookContext, targets)
            FileAction.Versions -> features.versions?.run(hookContext, targets)
            FileAction.Metadata -> features.metadata?.run(hookContext, targets)
            FileAction.CreateArchive -> features.createArchive?.run(hookContext, targets)
            FileAction.ExtractArchive -> features.extractArchive?.run(hookContext, targets)
            FileAction.RemoteDownload -> features.remoteDownload?.run(hookContext, targets)
            FileAction.Relocate -> features.relocate?.run(hookContext, targets)
            FileAction.Rename -> controller.dialog = FileDialog.Rename(first)
            FileAction.Move, FileAction.Copy -> navigator.pickFolder(
                FolderPickRequest(
                    purpose = if (action == FileAction.Move) FolderPickPurpose.Move else FolderPickPurpose.Copy,
                    sources = uris,
                    initialUri = CrUri.parseOrNull(first.path)?.parent()?.toString() ?: CrUri.my().toString(),
                    excluded = if (action == FileAction.Move) targets.filter { it.isFolder }.map { it.path } else emptyList(),
                ),
            )
            FileAction.Delete -> controller.dialog = FileDialog.Delete(
                targets,
                inTrash = CrUri.parseOrNull(first.path)?.fs == CrUri.FS_TRASH,
            )
            FileAction.Restore -> run(FileOperation.Restore(uris))
            FileAction.Pin -> {
                val pin = user?.pined?.firstOrNull { it.uri == first.path }
                if (pin == null) run(FileOperation.Pin(first.path, first.name)) else run(FileOperation.Unpin(first.path))
            }
            FileAction.Info -> navigator.showInfo(first)
            FileAction.DirectLink -> run(FileOperation.CreateSourceLinks(uris))
            FileAction.ResetThumbnail -> run(FileOperation.SetThumbnailDisabled(targets.filter { it.thumbDisabled }.map { it.path }, false))
            FileAction.DisableThumbnail -> run(FileOperation.SetThumbnailDisabled(uris, true))
            FileAction.GoToSharedLink -> first.metadata[MetadataKeys.SHARED_REDIRECT]
                ?.let { CrUri.parseOrNull(it)?.parent()?.toString() ?: it }
                ?.let(navigator::openFolder)
            FileAction.GoToParent -> CrUri.parseOrNull(first.path)?.parent()?.toString()?.let(navigator::openFolder)
            FileAction.MountPolicy -> controller.dialog = FileDialog.MountPolicy(first)
        }
    }

    SideEffect {
        controller.busy = state.busy
        controller.handler = { request ->
            when (request) {
                is FileActionRequest.ShowSheet -> controller.sheetTargets = request.targets
                is FileActionRequest.Perform -> if (request.targets.isNotEmpty()) perform(request.action, request.targets)
                is FileActionRequest.NewFolder -> controller.dialog = FileDialog.NewFolder(request.parentUri)
                is FileActionRequest.NewFile -> controller.dialog = FileDialog.NewFile(request.parentUri, request.option)
                FileActionRequest.EmptyTrash -> controller.dialog = FileDialog.EmptyTrash
                is FileActionRequest.PickResult -> when (request.result.purpose) {
                    FolderPickPurpose.Move -> run(FileOperation.Move(request.result.sources, request.result.destination))
                    FolderPickPurpose.Copy -> run(FileOperation.Copy(request.result.sources, request.result.destination))
                    // 保存分享由分享浏览页自己处理
                    FolderPickPurpose.SaveShare -> Unit
                }
            }
        }
    }

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is FileActionEffect.Succeeded -> {
                changed(effect.operation, effect.outcome)
                val outcome = effect.outcome
                if (outcome is FileOperationOutcome.SourceLinks) {
                    controller.dialog = FileDialog.SourceLinks(outcome)
                } else {
                    successMessage(context, effect.operation)?.let { snackbar.showSnackbar(it) }
                }
            }
            is FileActionEffect.Failed -> {
                // 部分失败由对话框列出明细；其余直接提示
                if (vm.state.value.partialFailures.isEmpty()) snackbar.showSnackbar(effect.error.message(context))
            }
        }
    }

    // 操作菜单
    val targets = controller.sheetTargets
    if (targets.isNotEmpty()) {
        val available = remember(targets, user, viewerResolver, inSearch, features) {
            visibleActions(targets, user, viewerResolver, inSearch, features)
        }
        val pinned = targets.size == 1 && user?.pined?.any { it.uri == targets.first().path } == true
        val pinLocked = targets.size == 1 && user?.pined?.any { it.uri == targets.first().path && it.locked } == true
        FileActionsSheet(
            targets = targets,
            actions = if (pinLocked) available - FileAction.Pin else available,
            pinned = pinned,
            onAction = { action ->
                controller.sheetTargets = emptyList()
                perform(action, targets)
            },
            onDismiss = { controller.sheetTargets = emptyList() },
        )
    }

    FileDialogs(
        dialog = controller.dialog,
        accountId = accountId,
        user = user,
        onDismiss = { controller.dialog = null },
        onRun = { op ->
            controller.dialog = null
            run(op)
        },
    )

    state.lockConflict?.let { prompt ->
        LockConflictDialog(
            prompt = prompt,
            onForceUnlock = { vm.on(FileActionIntent.ForceUnlockAndRetry) },
            onDismiss = { vm.on(FileActionIntent.DismissConflict) },
        )
    }
    if (state.partialFailures.isNotEmpty()) {
        PartialFailuresDialog(state.partialFailures, onDismiss = { vm.on(FileActionIntent.DismissFailures) })
    }
}

/** 菜单中实际显示的动作：规则允许，且需要后续阶段的动作已接入 */
internal fun visibleActions(
    targets: List<FileItem>,
    user: User?,
    viewerResolver: ViewerResolver?,
    inSearch: Boolean,
    features: FileFeatures,
): Set<FileAction> {
    val single = targets.singleOrNull()
    val hasViewer = single != null && viewerResolver?.candidatesFor(single)?.isNotEmpty() == true
    return FileActionPolicy.available(targets, user, hasViewer, inSearch).filterTo(LinkedHashSet()) { action ->
        when (action) {
            FileAction.Open -> features.preview != null
            FileAction.Download -> features.download != null
            FileAction.Share -> features.share != null
            FileAction.Versions -> features.versions != null
            FileAction.Metadata -> features.metadata != null
            FileAction.CreateArchive -> features.createArchive != null
            FileAction.ExtractArchive -> features.extractArchive != null
            FileAction.RemoteDownload -> features.remoteDownload != null
            FileAction.Relocate -> features.relocate != null
            else -> true
        }
    }
}

private fun successMessage(context: Context, op: FileOperation): String? = when (op) {
    is FileOperation.Rename -> context.getString(R.string.action_done_rename)
    is FileOperation.Move -> context.resources.getQuantityString(R.plurals.action_done_move, op.uris.size, op.uris.size)
    is FileOperation.Copy -> context.resources.getQuantityString(R.plurals.action_done_copy, op.uris.size, op.uris.size)
    is FileOperation.Delete -> context.resources.getQuantityString(R.plurals.action_done_delete, op.uris.size, op.uris.size)
    is FileOperation.Restore -> context.resources.getQuantityString(R.plurals.action_done_restore, op.uris.size, op.uris.size)
    FileOperation.EmptyTrash -> context.getString(R.string.action_done_empty_trash)
    is FileOperation.Pin -> context.getString(R.string.action_done_pin)
    is FileOperation.Unpin -> context.getString(R.string.action_done_unpin)
    is FileOperation.CreateFolder, is FileOperation.CreateFile -> context.getString(R.string.action_done_create)
    is FileOperation.SetThumbnailDisabled -> context.getString(R.string.action_done_thumbnail)
    is FileOperation.MountPolicy -> context.getString(R.string.action_done_mount_policy)
    else -> null
}

private fun FileAction.label(pinned: Boolean): Int = when (this) {
    FileAction.Enter -> R.string.action_enter
    FileAction.Open -> R.string.action_open
    FileAction.Download -> R.string.action_download
    FileAction.Share -> R.string.action_share
    FileAction.Rename -> R.string.action_rename
    FileAction.Move -> R.string.action_move
    FileAction.Copy -> R.string.action_copy
    FileAction.Delete -> R.string.action_delete
    FileAction.Restore -> R.string.action_restore
    FileAction.Pin -> if (pinned) R.string.action_unpin else R.string.action_pin
    FileAction.Info -> R.string.action_info
    FileAction.Versions -> R.string.action_versions
    FileAction.Metadata -> R.string.action_metadata
    FileAction.DirectLink -> R.string.action_direct_link
    FileAction.ResetThumbnail -> R.string.action_reset_thumbnail
    FileAction.DisableThumbnail -> R.string.action_disable_thumbnail
    FileAction.GoToSharedLink -> R.string.action_go_shared_link
    FileAction.GoToParent -> R.string.action_go_parent
    FileAction.MountPolicy -> R.string.action_mount_policy
    FileAction.Relocate -> R.string.action_relocate
    FileAction.CreateArchive -> R.string.action_create_archive
    FileAction.ExtractArchive -> R.string.action_extract_archive
    FileAction.RemoteDownload -> R.string.action_remote_download
}

internal fun FileAction.icon(pinned: Boolean): ImageVector = when (this) {
    FileAction.Enter -> Icons.Filled.FolderOpen
    FileAction.Open -> Icons.AutoMirrored.Filled.OpenInNew
    FileAction.Download -> Icons.Filled.Download
    FileAction.Share -> Icons.Filled.Share
    FileAction.Rename -> Icons.Filled.DriveFileRenameOutline
    FileAction.Move -> Icons.AutoMirrored.Filled.DriveFileMove
    FileAction.Copy -> Icons.Filled.ContentCopy
    FileAction.Delete -> Icons.Filled.Delete
    FileAction.Restore -> Icons.Filled.Restore
    FileAction.Pin -> if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin
    FileAction.Info -> Icons.Filled.Info
    FileAction.Versions -> Icons.Filled.History
    FileAction.Metadata -> Icons.AutoMirrored.Filled.Label
    FileAction.DirectLink -> Icons.Filled.Link
    FileAction.ResetThumbnail -> Icons.Filled.Image
    FileAction.DisableThumbnail -> Icons.Filled.HideImage
    FileAction.GoToSharedLink -> Icons.Filled.Share
    FileAction.GoToParent -> Icons.Filled.FolderOpen
    FileAction.MountPolicy -> Icons.Filled.Policy
    FileAction.Relocate -> Icons.Filled.SwapHoriz
    FileAction.CreateArchive -> Icons.Filled.Archive
    FileAction.ExtractArchive -> Icons.Filled.Unarchive
    FileAction.RemoteDownload -> Icons.Filled.CloudDownload
}

@Composable
internal fun FileAction.labelText(pinned: Boolean = false): String = stringResource(label(pinned))

/** 操作菜单（底部弹层）：头部显示目标，下面是可用动作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionsSheet(
    targets: List<FileItem>,
    actions: Set<FileAction>,
    pinned: Boolean,
    onAction: (FileAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "header", contentType = "header") {
                val single = targets.singleOrNull()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (single != null) FileTypeIconView(single, 40.dp)
                    },
                    headlineContent = {
                        Text(
                            single?.name ?: stringResource(R.string.selection_count, targets.size),
                            maxLines = 2,
                            overflow = TextOverflow.MiddleEllipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                )
                HorizontalDivider()
            }
            if (actions.isEmpty()) {
                item(key = "none", contentType = "empty") {
                    Text(
                        stringResource(R.string.action_none_available),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(actions.toList(), key = { it.name }, contentType = { "action" }) { action ->
                val destructive = action == FileAction.Delete
                ListItem(
                    modifier = Modifier.clickable { onAction(action) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(
                            action.icon(pinned),
                            contentDescription = null,
                            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = {
                        Text(
                            action.labelText(pinned),
                            color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                )
            }
        }
    }
}

/** 复制文本到剪贴板 */
@Composable
internal fun rememberCopyToClipboard(snackbar: SnackbarHostState?): suspend (String) -> Unit {
    val clipboard = LocalClipboard.current
    val resources = LocalResources.current
    return remember(clipboard, snackbar, resources) {
        { text: String ->
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text)))
            snackbar?.showSnackbar(resources.getString(R.string.copied))
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun InfoLine(label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (trailing != null) {
            Row(Modifier.size(48.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}
