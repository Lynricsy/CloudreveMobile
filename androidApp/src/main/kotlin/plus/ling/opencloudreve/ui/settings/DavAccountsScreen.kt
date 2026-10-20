package plus.ling.opencloudreve.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.flow.Flow
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.settings.DavAccountDraft
import plus.ling.opencloudreve.core.presentation.settings.DavAccountItem
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsEffect
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsIntent
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsState
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/** 编辑器打开时 [DavEditorDraft.id] 的取值：新建 */
private const val NEW_ID = ""

/** 编辑器草稿（可存入 saveable：选目录跳转再返回后恢复） */
private data class DavEditorDraft(
    val id: String,
    val name: String,
    val uri: String,
    val readonly: Boolean,
    val proxy: Boolean,
    val disableSysFiles: Boolean,
) {
    fun toDraft() = DavAccountDraft(name, uri, readonly, proxy, disableSysFiles)

    fun toList(): List<Any> = listOf(id, name, uri, readonly, proxy, disableSysFiles)

    companion object {
        fun fromList(l: List<Any>) = DavEditorDraft(
            l[0] as String, l[1] as String, l[2] as String, l[3] as Boolean, l[4] as Boolean, l[5] as Boolean,
        )
    }
}

/**
 * WebDAV 账号：连接信息（地址/用户名）、账号列表（复制地址/用户名/密码、编辑、删除）与新建/编辑表单。
 * 根目录用目录选择器选择：[onPickFolder] 打开选择器，结果经 [pickedUri] 送回（[onPickedConsumed] 清除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DavAccountsScreen(
    state: DavAccountsState,
    effects: Flow<DavAccountsEffect>,
    onIntent: (DavAccountsIntent) -> Unit,
    onBack: () -> Unit,
    onPickFolder: (initialUri: String) -> Unit,
    pickedUri: String?,
    onPickedConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var editorList by rememberSaveable { mutableStateOf<List<Any>?>(null) }
    val editor = editorList?.let(DavEditorDraft::fromList)
    var deleting by remember { mutableStateOf<DavAccountItem?>(null) }

    // 目录选择器返回的根目录写回草稿
    LaunchedEffect(pickedUri) {
        val picked = pickedUri ?: return@LaunchedEffect
        editorList = editor?.copy(uri = picked)?.toList()
        onPickedConsumed()
    }

    // Snackbar 在独立协程中显示：带操作按钮的 Snackbar 默认不自动消失，不能阻塞后续事件
    val scope = rememberCoroutineScope()
    CollectEffects(effects) { effect ->
        when (effect) {
            is DavAccountsEffect.Saved -> {
                editorList = null
                scope.launch {
                    if (effect.created) {
                        val result = snackbar.showSnackbar(
                            resources.getString(R.string.dav_created),
                            actionLabel = resources.getString(R.string.dav_copy_password),
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            copyToClipboard(context, resources.getString(R.string.dav_password), effect.item.credentials.password, sensitive = true)
                        }
                    } else {
                        snackbar.showSnackbar(resources.getString(R.string.settings_saved))
                    }
                }
            }
            is DavAccountsEffect.Deleted -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.dav_deleted)) }
            is DavAccountsEffect.Failed -> scope.launch { snackbar.showSnackbar(effect.error.message(context)) }
        }
    }

    Scaffold(
        topBar = { SmallTopBar(stringResource(R.string.dav_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.webdavEnabled) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editorList = DavEditorDraft(NEW_ID, "", CrUri.my().toString(), false, false, false).toList()
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.dav_new)) },
                    expanded = true,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.davUrl.isNotEmpty(),
            onRefresh = { onIntent(DavAccountsIntent.Refresh) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
                state.error?.let { LoadError(it) { onIntent(DavAccountsIntent.Refresh) } }
                if (state.davUrl.isNotEmpty()) ConnectionCard(state)
                if (!state.loading && state.error == null && !state.webdavEnabled) {
                    Text(
                        stringResource(R.string.dav_disabled),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                SectionHeader(stringResource(R.string.dav_accounts))
                if (!state.loading && state.error == null && state.items.isEmpty()) {
                    Text(
                        stringResource(R.string.dav_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                state.items.forEach { item ->
                    DavAccountRow(
                        item = item,
                        deleting = state.deletingId == item.id,
                        onEdit = {
                            val d = DavAccountDraft.from(item.account)
                            editorList = DavEditorDraft(item.id, d.name, d.uri, d.readonly, d.proxy, d.disableSysFiles).toList()
                        },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    editor?.let { draft ->
        DavEditorDialog(
            draft = draft,
            canProxy = state.canProxy,
            saving = state.saving,
            onChange = { editorList = it.toList() },
            onPickFolder = { onPickFolder(draft.uri) },
            onSave = {
                if (draft.id == NEW_ID) onIntent(DavAccountsIntent.Create(draft.toDraft()))
                else onIntent(DavAccountsIntent.Update(draft.id, draft.toDraft()))
            },
            onDismiss = { editorList = null },
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.dav_delete),
            message = stringResource(R.string.dav_delete_message, item.account.name),
            confirm = stringResource(R.string.settings_delete),
            onConfirm = {
                deleting = null
                onIntent(DavAccountsIntent.Delete(item.id))
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ConnectionCard(state: DavAccountsState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.dav_connection),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SettingRow(
                title = state.davUrl,
                subtitle = stringResource(R.string.dav_address),
                icon = Icons.Outlined.Link,
                trailing = {
                    IconButton(onClick = { copyToClipboard(context, resources.getString(R.string.dav_address), state.davUrl) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.dav_copy_address))
                    }
                },
            )
            SettingRow(
                title = state.username,
                subtitle = stringResource(R.string.dav_username),
                icon = Icons.Outlined.Person,
                trailing = {
                    IconButton(onClick = { copyToClipboard(context, resources.getString(R.string.dav_username), state.username) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.dav_copy_username))
                    }
                },
            )
        }
    }
}

@Composable
private fun DavAccountRow(item: DavAccountItem, deleting: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    Column {
        SettingRow(
            title = item.account.name,
            subtitle = davRootLabel(item.account.uri),
            icon = Icons.Outlined.Dns,
            busy = deleting,
            onClick = onEdit,
            trailing = {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.dav_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_copy_address)) },
                            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                            onClick = {
                                menu = false
                                copyToClipboard(context, resources.getString(R.string.dav_address), item.credentials.url)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_copy_username)) },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                            onClick = {
                                menu = false
                                copyToClipboard(context, resources.getString(R.string.dav_username), item.credentials.username)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_copy_password)) },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            onClick = {
                                menu = false
                                copyToClipboard(context, resources.getString(R.string.dav_password), item.credentials.password, sensitive = true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            },
        )
        val flags = listOfNotNull(
            stringResource(R.string.dav_readonly).takeIf { item.readonly },
            stringResource(R.string.dav_proxy).takeIf { item.proxy },
            stringResource(R.string.dav_disable_sys_files).takeIf { item.disableSysFiles },
        )
        if (flags.isNotEmpty()) {
            Row(Modifier.padding(start = 72.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flags.forEach { AssistChip(onClick = {}, label = { Text(it) }, enabled = false) }
            }
        }
    }
}

@Composable
private fun davRootLabel(uri: String): String {
    val parsed = CrUri.parseOrNull(uri) ?: return uri
    val base = when (parsed.fs) {
        CrUri.FS_SHARE -> stringResource(R.string.dav_root_share, parsed.id.orEmpty())
        else -> stringResource(R.string.dav_root_my)
    }
    return if (parsed.isRoot()) base else base + parsed.path
}

@Composable
private fun DavEditorDialog(
    draft: DavEditorDraft,
    canProxy: Boolean,
    saving: Boolean,
    onChange: (DavEditorDraft) -> Unit,
    onPickFolder: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (draft.id == NEW_ID) R.string.dav_new else R.string.dav_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it.take(255))) },
                    label = { Text(stringResource(R.string.dav_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.dav_root), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Text(
                        davRootLabel(draft.uri),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                }
                EditorSwitch(stringResource(R.string.dav_readonly), stringResource(R.string.dav_readonly_desc), draft.readonly) {
                    onChange(draft.copy(readonly = it))
                }
                if (canProxy) {
                    EditorSwitch(stringResource(R.string.dav_proxy), stringResource(R.string.dav_proxy_desc), draft.proxy) {
                        onChange(draft.copy(proxy = it))
                    }
                }
                EditorSwitch(
                    stringResource(R.string.dav_disable_sys_files),
                    stringResource(R.string.dav_disable_sys_files_desc),
                    draft.disableSysFiles,
                ) { onChange(draft.copy(disableSysFiles = it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.name.isNotBlank() && !saving) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 整行可点的开关（标题与说明都能切换） */
@Composable
private fun EditorSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
