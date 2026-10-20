package plus.ling.opencloudreve.ui.workflow

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.di.RemoteDownloadParams
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.WorkflowNode
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskEffect
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskIntent
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskMode
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskState
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskViewModel
import plus.ling.opencloudreve.core.presentation.workflow.RemoteDownloadCreateEffect
import plus.ling.opencloudreve.core.presentation.workflow.RemoteDownloadCreateIntent
import plus.ling.opencloudreve.core.presentation.workflow.RemoteDownloadCreateViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.FolderPickerScreen
import plus.ling.opencloudreve.ui.files.displayPath

/**
 * 新建远程下载：多行 URL/磁力，或种子文件（[torrentUri]）；目标目录用目录选择器；有 `select_node` 权限时可选节点。
 */
@Composable
fun RemoteDownloadDialog(
    accountId: String,
    dstUri: String,
    torrentUri: String?,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val vm: RemoteDownloadCreateViewModel = koinViewModel(key = "remote-download:$dstUri:$torrentUri") {
        parametersOf(RemoteDownloadParams(accountId, dstUri, torrentUri))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is RemoteDownloadCreateEffect.Created -> {
                val created = effect.tasks.size
                Toast.makeText(context, resources.getQuantityString(R.plurals.remote_download_created, created, created), Toast.LENGTH_SHORT).show()
                if (effect.failedCount == 0) onCreated()
            }
            is RemoteDownloadCreateEffect.Failed -> error = effect.error.message(context)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_download_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.permitted) Text(stringResource(R.string.task_not_permitted), color = MaterialTheme.colorScheme.error)
                val torrent = state.torrentName
                if (torrent != null) {
                    ListItem(
                        headlineContent = { Text(torrent, maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
                        overlineContent = { Text(stringResource(R.string.remote_download_torrent)) },
                        trailingContent = {
                            IconButton(onClick = { vm.on(RemoteDownloadCreateIntent.SetTorrent(null)) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remote_download_use_urls))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                } else {
                    OutlinedTextField(
                        value = state.urlsText,
                        onValueChange = { vm.on(RemoteDownloadCreateIntent.SetUrls(it)) },
                        label = { Text(stringResource(R.string.remote_download_urls)) },
                        placeholder = { Text(stringResource(R.string.remote_download_urls_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    )
                }
                DestinationRow(stringResource(R.string.task_destination), state.dstUri) { picking = true }
                if (state.canSelectNode) {
                    NodeSelector(state.nodes, state.selectedNodeId, state.loadingNodes) { vm.on(RemoteDownloadCreateIntent.SelectNode(it)) }
                }
                state.failures.forEach { failure ->
                    Text("${failure.uri}: ${failure.error.message()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.submitting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = null; vm.on(RemoteDownloadCreateIntent.Submit) }, enabled = state.canSubmit) {
                Text(stringResource(R.string.remote_download_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) {
        FolderPickDialog(accountId, state.dstUri, onPicked = { vm.on(RemoteDownloadCreateIntent.SetDestination(it)); picking = false }, onCancel = { picking = false })
    }
}

/**
 * 压缩 / 解压 / 转移存储策略对话框。
 * @param srcUris 压缩/转移：各文件的链接目标；解压：压缩包
 * @param dstUri 压缩包所在目录 / 解压目标目录（转移忽略）
 */
@Composable
fun ArchiveTaskDialog(
    accountId: String,
    mode: ArchiveTaskMode,
    srcUris: List<String>,
    dstUri: String,
    initialArchiveName: String?,
    onDismiss: () -> Unit,
) {
    val vm: ArchiveTaskViewModel = koinViewModel(key = "archive:$mode:${srcUris.hashCode()}") {
        parametersOf(accountId, mode, srcUris, dstUri, emptyList<String>())
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        if (mode == ArchiveTaskMode.COMPRESS && initialArchiveName != null && vm.state.value.archiveName == ArchiveTaskState.DEFAULT_ARCHIVE_NAME) {
            vm.on(ArchiveTaskIntent.SetArchiveName(initialArchiveName))
        }
    }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ArchiveTaskEffect.Created -> {
                Toast.makeText(context, R.string.task_created, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            is ArchiveTaskEffect.Failed -> error = effect.error.message(context)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (mode) {
                        ArchiveTaskMode.COMPRESS -> R.string.archive_compress_title
                        ArchiveTaskMode.EXTRACT -> R.string.archive_extract_title
                        ArchiveTaskMode.RELOCATE -> R.string.archive_relocate_title
                    },
                ),
            )
        },
        text = { ArchiveTaskForm(state, error, vm::on, onPickDestination = { picking = true }) },
        confirmButton = {
            TextButton(onClick = { error = null; vm.on(ArchiveTaskIntent.Submit) }, enabled = state.canSubmit) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) {
        FolderPickDialog(accountId, state.dstUri, onPicked = { vm.on(ArchiveTaskIntent.SetDestination(it)); picking = false }, onCancel = { picking = false })
    }
}

@Composable
private fun ArchiveTaskForm(state: ArchiveTaskState, error: String?, onIntent: (ArchiveTaskIntent) -> Unit, onPickDestination: () -> Unit) {
    val count = state.srcUris.size
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (count > 1) pluralStringResource(R.plurals.archive_sources, count, state.sourceName, count - 1) else state.sourceName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (!state.permitted) Text(stringResource(R.string.task_not_permitted), color = MaterialTheme.colorScheme.error)
        if (state.loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.loadError?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        when (state.mode) {
            ArchiveTaskMode.COMPRESS -> {
                OutlinedTextField(
                    value = state.archiveName,
                    onValueChange = { onIntent(ArchiveTaskIntent.SetArchiveName(it)) },
                    label = { Text(stringResource(R.string.archive_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DestinationRow(stringResource(R.string.archive_save_to), state.dstUri, onPickDestination)
            }
            ArchiveTaskMode.EXTRACT -> {
                DestinationRow(stringResource(R.string.archive_extract_to), state.dstUri, onPickDestination)
                if (state.showEncoding) EncodingSelector(state.encoding) { onIntent(ArchiveTaskIntent.SetEncoding(it)) }
                if (state.showPassword) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { onIntent(ArchiveTaskIntent.SetPassword(it)) },
                        label = { Text(stringResource(R.string.archive_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ArchiveTaskMode.RELOCATE -> {
                Text(stringResource(R.string.archive_relocate_target), style = MaterialTheme.typography.labelLarge)
                state.policies.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = option.selectable) { onIntent(ArchiveTaskIntent.SelectPolicy(option.id)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.selectedPolicyId == option.id, onClick = null, enabled = option.selectable)
                        Text(
                            option.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
        if (state.canSelectNode && state.mode != ArchiveTaskMode.RELOCATE) {
            NodeSelector(state.nodes, state.selectedNodeId, loading = false) { onIntent(ArchiveTaskIntent.SelectNode(it)) }
        }
        if (state.submitting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 目标目录行：点按打开目录选择器 */
@Composable
private fun DestinationRow(label: String, uri: String, onClick: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(label) },
        headlineContent = { Text(CrUri.parseOrNull(uri)?.let { displayPath(context, it) } ?: uri, maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        trailingContent = { Text(stringResource(R.string.action_change), color = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun NodeSelector(nodes: List<WorkflowNode>, selectedId: String?, loading: Boolean, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = nodes.firstOrNull { it.id == selectedId }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            overlineContent = { Text(stringResource(R.string.task_node)) },
            headlineContent = { Text(selected?.name ?: stringResource(R.string.task_node_auto)) },
            trailingContent = if (loading) {
                { CircularProgressIndicator(Modifier.padding(4.dp)) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.task_node_auto)) }, onClick = { open = false; onSelect(null) })
            nodes.forEach { node ->
                DropdownMenuItem(text = { Text(node.name.ifEmpty { node.id }) }, onClick = { open = false; onSelect(node.id) })
            }
        }
    }
}

@Composable
private fun EncodingSelector(encoding: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            overlineContent = { Text(stringResource(R.string.archive_encoding)) },
            headlineContent = { Text(encoding.ifEmpty { stringResource(R.string.archive_encoding_default) }) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            (listOf("") + ArchiveTaskState.ENCODINGS).forEach { enc ->
                DropdownMenuItem(
                    text = { Text(enc.ifEmpty { stringResource(R.string.archive_encoding_default) }) },
                    onClick = { open = false; onSelect(enc) },
                )
            }
        }
    }
}

/** 全屏目录选择器（复用文件 Tab 的 [FolderPickerScreen]） */
@Composable
internal fun FolderPickDialog(accountId: String, initialUri: String, onPicked: (String) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FolderPickerScreen(
            accountId = accountId,
            initialUri = initialUri,
            excluded = emptyList(),
            confirmLabel = stringResource(R.string.picker_select_here),
            onPicked = onPicked,
            onCancel = onCancel,
        )
    }
}
