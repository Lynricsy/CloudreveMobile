package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.files.FileOperation
import plus.ling.opencloudreve.core.presentation.files.LockConflictPrompt
import plus.ling.opencloudreve.core.presentation.files.OperationFailure
import plus.ling.opencloudreve.core.presentation.settings.PoliciesViewModel
import plus.ling.opencloudreve.ui.common.message

/** 名称中不允许的字符（与服务端校验一致：不能含 `/` 与 `\`） */
private fun isValidName(name: String): Boolean {
    val n = name.trim()
    return n.isNotEmpty() && n != "." && n != ".." && '/' !in n && '\\' !in n
}

@Composable
internal fun FileDialogs(
    dialog: FileDialog?,
    accountId: String,
    user: User?,
    onDismiss: () -> Unit,
    onRun: (FileOperation) -> Unit,
) {
    when (dialog) {
        null -> Unit
        is FileDialog.Rename -> NameInputDialog(
            title = stringResource(R.string.action_rename),
            initial = dialog.file.name,
            selectBaseName = !dialog.file.isFolder,
            confirm = stringResource(R.string.action_rename),
            onDismiss = onDismiss,
            onConfirm = { onRun(FileOperation.Rename(dialog.file.path, it)) },
        )
        is FileDialog.NewFolder -> NameInputDialog(
            title = stringResource(R.string.new_folder),
            initial = stringResource(R.string.new_folder_default_name),
            selectBaseName = false,
            confirm = stringResource(R.string.action_create),
            onDismiss = onDismiss,
            onConfirm = { onRun(FileOperation.CreateFolder(dialog.parentUri, it)) },
        )
        is FileDialog.NewFile -> NameInputDialog(
            title = stringResource(R.string.new_file_title, dialog.option.template.displayName.ifBlank { dialog.option.template.ext }),
            initial = stringResource(R.string.new_file_default_name) + "." + dialog.option.template.ext,
            selectBaseName = true,
            confirm = stringResource(R.string.action_create),
            onDismiss = onDismiss,
            onConfirm = { onRun(FileOperation.CreateFile(dialog.parentUri, it)) },
        )
        is FileDialog.Delete -> DeleteDialog(dialog, user, onDismiss, onRun)
        FileDialog.EmptyTrash -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.trash_empty_title)) },
            text = { Text(stringResource(R.string.trash_empty_message)) },
            confirmButton = {
                TextButton(onClick = { onRun(FileOperation.EmptyTrash) }) {
                    Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        is FileDialog.SourceLinks -> SourceLinksDialog(dialog, onDismiss)
        is FileDialog.MountPolicy -> MountPolicyDialog(accountId, dialog.folder, onDismiss) { policyId ->
            onRun(FileOperation.MountPolicy(dialog.folder.path, policyId))
        }
    }
}

/**
 * 目录挂载存储策略：选择可用策略之一，或恢复继承（空串）。
 * 策略列表来自 [PoliciesViewModel]（`GET /user/setting/policies`）。
 */
@Composable
private fun MountPolicyDialog(accountId: String, folder: FileItem, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val vm: PoliciesViewModel = koinViewModel(key = "policies") { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_mount_policy)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.mount_policy_message, folder.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val error = state.error
                when {
                    state.loading && state.items.isEmpty() -> LoadingMore()
                    error != null && state.items.isEmpty() -> Text(error.message(), color = MaterialTheme.colorScheme.error)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item(key = "inherit") {
                            PolicyChoice(stringResource(R.string.mount_policy_inherit), null) { onPick("") }
                        }
                        items(state.items, key = { it.policy.id }) { item ->
                            PolicyChoice(
                                item.policy.name,
                                if (item.isDefault) stringResource(R.string.mount_policy_default) else null,
                            ) { onPick(item.policy.id) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PolicyChoice(name: String, note: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(name) },
        supportingContent = note?.let { { Text(it) } },
    )
}

/** 名称输入（重命名/新建）；文件默认只选中主文件名 */
@Composable
internal fun NameInputDialog(
    title: String,
    initial: String,
    selectBaseName: Boolean,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val selectionEnd = if (selectBaseName) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, selectionEnd))) }
    val focus = remember { FocusRequester() }
    val valid = isValidName(value.text)
    val submit = { if (valid) onConfirm(value.text.trim()) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = value.text.isNotEmpty() && !valid,
                supportingText = if (value.text.isNotEmpty() && !valid) {
                    { Text(stringResource(R.string.name_invalid)) }
                } else {
                    null
                },
                label = { Text(stringResource(R.string.name_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * 删除确认：回收站内总是彻底删除；其它位置默认移入回收站，可勾选“彻底删除”，
 * 有 advance_delete 权限时在彻底删除/回收站项上提供“仅解除链接”（规格 §7.2）。
 */
@Composable
private fun DeleteDialog(
    dialog: FileDialog.Delete,
    user: User?,
    onDismiss: () -> Unit,
    onRun: (FileOperation) -> Unit,
) {
    var permanent by rememberSaveable { mutableStateOf(dialog.inTrash) }
    var unlink by rememberSaveable { mutableStateOf(false) }
    val hasTrashFile = dialog.files.any { MetadataKeys.RESTORE_URI in it.metadata }
    val canUnlink = FileActionPolicy.canUnlink(user, permanent, hasTrashFile)
    val softDeleteAllowed = !dialog.inTrash && dialog.files.any { it.can(NavigatorCapability.SOFT_DELETE) }
    val retentionDays = (user?.group?.trashRetention ?: 0) / 86_400
    val count = dialog.files.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count == 1) {
                    stringResource(R.string.delete_title_single, dialog.files.first().name)
                } else {
                    pluralStringResource(R.plurals.delete_title_multiple, count, count)
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        permanent -> stringResource(R.string.delete_message_permanent)
                        retentionDays > 0 -> pluralStringResource(R.plurals.delete_message_trash_retention, retentionDays, retentionDays)
                        else -> stringResource(R.string.delete_message_trash)
                    },
                )
                if (softDeleteAllowed) {
                    CheckRow(stringResource(R.string.delete_permanently), permanent) {
                        permanent = it
                        if (!it) unlink = false
                    }
                }
                if (canUnlink) {
                    CheckRow(stringResource(R.string.delete_unlink_only), unlink) { unlink = it }
                    if (unlink) {
                        Text(
                            stringResource(R.string.delete_unlink_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onRun(
                    FileOperation.Delete(
                        uris = dialog.files.map { it.path },
                        unlink = unlink && canUnlink,
                        skipSoftDelete = permanent || dialog.inTrash || !softDeleteAllowed,
                    ),
                )
            }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 直链结果：可逐条复制；失败项列出原因 */
@Composable
private fun SourceLinksDialog(dialog: FileDialog.SourceLinks, onDismiss: () -> Unit) {
    val copy = rememberCopyToClipboard(null)
    val scope = rememberCoroutineScope()
    val links = dialog.outcome.links
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_direct_link)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(links, key = { it.link }) { link ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(link.link, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { scope.launch { copy(link.link) } }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy_link))
                        }
                    }
                }
                items(dialog.outcome.failures, key = { "f:" + it.uri }) { failure ->
                    FailureLine(failure)
                }
            }
        },
        confirmButton = {
            if (links.size > 1) {
                TextButton(onClick = { scope.launch { copy(links.joinToString("\n") { it.link }) } }) {
                    Text(stringResource(R.string.action_copy_all))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun FailureLine(failure: OperationFailure) {
    Column {
        Text(
            CrUri.parseOrNull(failure.uri)?.name()?.ifEmpty { failure.uri } ?: failure.uri,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(failure.error.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** 锁冲突（40073）：列出被锁定的路径与占用方，可强制解锁后重试 */
@Composable
internal fun LockConflictDialog(prompt: LockConflictPrompt, onForceUnlock: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lock_conflict_message))
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(prompt.conflicts.size) { index ->
                        val c = prompt.conflicts[index]
                        val owner = when (c.owner?.application?.type) {
                            "webdav" -> stringResource(R.string.lock_owner_webdav)
                            "viewer" -> stringResource(R.string.lock_owner_viewer)
                            null, "" -> stringResource(R.string.lock_owner_unknown)
                            else -> c.owner?.application?.type.orEmpty()
                        }
                        Column(Modifier.fillMaxWidth()) {
                            Text(c.path ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                            Text(
                                stringResource(R.string.lock_owner, owner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onForceUnlock, enabled = prompt.conflicts.any { !it.token.isNullOrEmpty() }) {
                Text(stringResource(R.string.lock_force_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 批量操作部分失败（40081）明细 */
@Composable
internal fun PartialFailuresDialog(failures: List<OperationFailure>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.partial_failures_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(failures, key = { it.uri }) { FailureLine(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
