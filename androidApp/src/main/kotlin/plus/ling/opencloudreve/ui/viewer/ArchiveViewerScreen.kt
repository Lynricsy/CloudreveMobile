package plus.ling.opencloudreve.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.viewer.ViewerArchiveEffect
import plus.ling.opencloudreve.core.presentation.viewer.ViewerArchiveIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerArchiveViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskEffect
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskIntent
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskMode
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.FolderPickerScreen
import plus.ling.opencloudreve.ui.files.displayPath
import plus.ling.opencloudreve.ui.files.formatBytes
import plus.ling.opencloudreve.ui.files.formatDateTime

/** 解压请求（来自压缩包预览的 [ViewerArchiveEffect.ExtractRequested]） */
private data class ExtractRequest(val srcUri: String, val dstUri: String, val encoding: String?, val mask: List<String>)

/** 压缩包预览：树形浏览（目录优先）、文件名编码切换、多选后“解压到…”（在本页发起解压任务） */
@Composable
internal fun ArchiveViewerScreen(
    accountId: String,
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerArchiveViewModel = koinViewModel(key = "archive:${params.file.path}:${params.version}") { parametersOf(params) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var extract by remember { mutableStateOf<ExtractRequest?>(null) }
    var showEncoding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ViewerArchiveEffect.ExtractRequested -> {
                val parent = effect.file.uri()?.parent()?.toString() ?: (CrUri.SCHEME_PREFIX + CrUri.FS_MY)
                extract = ExtractRequest(effect.file.linkedUri, parent, effect.encoding, effect.mask)
            }
        }
    }
    BackHandler(enabled = state.canGoUp) { vm.on(ViewerArchiveIntent.Up) }

    ViewerScaffold(
        title = state.file.name,
        subtitle = if (state.breadcrumbs.isEmpty()) null else "/" + state.breadcrumbs.joinToString("/"),
        menu = menu,
        onClose = onClose,
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { showEncoding = true }) {
                Icon(Icons.Default.Translate, contentDescription = stringResource(R.string.viewer_archive_encoding))
            }
        },
        bottomBar = {
            if (state.canExtract) {
                BottomAppBar {
                    Text(
                        if (state.selection.isEmpty()) stringResource(R.string.viewer_archive_all)
                        else stringResource(R.string.viewer_archive_selected, state.selection.size),
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                    if (state.selection.isNotEmpty()) {
                        TextButton(onClick = { vm.on(ViewerArchiveIntent.ClearSelection) }) { Text(stringResource(R.string.viewer_archive_clear)) }
                    }
                    Button(
                        onClick = { vm.on(if (state.selection.isEmpty()) ViewerArchiveIntent.ExtractAll else ViewerArchiveIntent.ExtractSelected) },
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = null)
                        Text(stringResource(R.string.viewer_archive_extract_to), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val err = state.error
            when {
                state.loading -> CenteredProgress()
                err != null -> CenteredError(err.message(), onRetry = { vm.on(ViewerArchiveIntent.Refresh) })
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.encoding != null) {
                        AssistChip(
                            onClick = { showEncoding = true },
                            label = { Text(stringResource(R.string.viewer_archive_encoding_value, state.encoding!!)) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    if (state.canGoUp) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                            TextButton(onClick = { vm.on(ViewerArchiveIntent.Open("")) }) { Text(state.file.name) }
                            state.breadcrumbs.forEachIndexed { i, seg ->
                                Text("/", modifier = Modifier.align(Alignment.CenterVertically))
                                TextButton(onClick = { vm.on(ViewerArchiveIntent.Open(state.breadcrumbs.take(i + 1).joinToString("/"))) }) { Text(seg) }
                            }
                        }
                        HorizontalDivider()
                    }
                    val children = state.children
                    if (children.isEmpty()) {
                        CenteredError(stringResource(R.string.viewer_archive_empty), onRetry = null)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(children, key = { it.path }) { entry ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (entry.isDirectory) {
                                        vm.on(ViewerArchiveIntent.Open(entry.path))
                                    } else if (state.canExtract) {
                                        vm.on(ViewerArchiveIntent.ToggleSelection(entry.path))
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
                                supportingContent = {
                                    val parts = buildList {
                                        if (!entry.isDirectory) add(formatBytes(context, entry.size))
                                        entry.updatedAt?.let { add(formatDateTime(it)) }
                                    }
                                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                                },
                                trailingContent = if (state.canExtract) {
                                    {
                                        Checkbox(
                                            checked = entry.path in state.selection,
                                            onCheckedChange = { vm.on(ViewerArchiveIntent.ToggleSelection(entry.path)) },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEncoding) {
        EncodingDialog(
            current = state.encoding,
            options = state.encodingOptions,
            onDismiss = { showEncoding = false },
            onSelect = {
                showEncoding = false
                vm.on(ViewerArchiveIntent.SetEncoding(it))
            },
        )
    }
    extract?.let { request ->
        ExtractDialog(
            accountId = accountId,
            request = request,
            onDismiss = { extract = null },
            onCreated = {
                extract = null
                vm.on(ViewerArchiveIntent.ClearSelection)
                // 对话框随即离开组合，提示由本页的协程显示
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.viewer_archive_task_created)) }
            },
            snackbar = snackbar,
        )
    }
}

@Composable
private fun EncodingDialog(current: String?, options: List<String>, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_archive_encoding)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { EncodingRow(stringResource(R.string.viewer_archive_encoding_default), current == null) { onSelect(null) } }
                items(options) { enc -> EncodingRow(enc, current == enc) { onSelect(enc) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EncodingRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** 解压任务表单：目标目录（目录选择器）、密码、节点 */
@Composable
private fun ExtractDialog(
    accountId: String,
    request: ExtractRequest,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    snackbar: SnackbarHostState,
    vm: ArchiveTaskViewModel = koinViewModel(key = "extract:${request.srcUri}:${request.mask.hashCode()}") {
        parametersOf(accountId, ArchiveTaskMode.EXTRACT, listOf(request.srcUri), request.dstUri, request.mask)
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var nodeMenu by remember { mutableStateOf(false) }

    // 沿用预览中选择的文件名编码
    androidx.compose.runtime.LaunchedEffect(request.encoding) {
        request.encoding?.let { vm.on(ArchiveTaskIntent.SetEncoding(it)) }
    }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ArchiveTaskEffect.Created -> onCreated()
            is ArchiveTaskEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_archive_extract_to)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request.mask.isNotEmpty()) {
                    Text(stringResource(R.string.viewer_archive_selected, request.mask.size), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.viewer_archive_destination), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Text(
                        CrUri.parseOrNull(state.dstUri)?.let { displayPath(context, it) } ?: state.dstUri,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (state.showPassword) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { vm.on(ArchiveTaskIntent.SetPassword(it)) },
                        label = { Text(stringResource(R.string.viewer_archive_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.canSelectNode && state.nodes.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { nodeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                state.nodes.firstOrNull { it.id == state.selectedNodeId }?.name
                                    ?: stringResource(R.string.viewer_archive_node_auto),
                            )
                        }
                        androidx.compose.material3.DropdownMenu(expanded = nodeMenu, onDismissRequest = { nodeMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.viewer_archive_node_auto)) }, onClick = {
                                nodeMenu = false
                                vm.on(ArchiveTaskIntent.SelectNode(null))
                            })
                            state.nodes.forEach { node ->
                                DropdownMenuItem(text = { Text(node.name) }, onClick = {
                                    nodeMenu = false
                                    vm.on(ArchiveTaskIntent.SelectNode(node.id))
                                })
                            }
                        }
                    }
                }
                state.loadError?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.on(ArchiveTaskIntent.Submit) }, enabled = state.canSubmit) {
                Text(stringResource(R.string.viewer_archive_extract))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (picking) {
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FolderPickerScreen(
                accountId = accountId,
                initialUri = state.dstUri,
                excluded = emptyList(),
                confirmLabel = stringResource(R.string.viewer_archive_extract_here),
                onPicked = {
                    picking = false
                    vm.on(ArchiveTaskIntent.SetDestination(it))
                },
                onCancel = { picking = false },
            )
        }
    }
}
