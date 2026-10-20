package plus.ling.opencloudreve.ui.transfer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.transfer.TransferListEffect
import plus.ling.opencloudreve.core.presentation.transfer.TransferListIntent
import plus.ling.opencloudreve.core.presentation.transfer.TransferListState
import plus.ling.opencloudreve.core.presentation.transfer.TransferListViewModel
import plus.ling.opencloudreve.core.presentation.transfer.TransferRow
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.transfer.DownloadTargets
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.workflow.BackgroundTasks

/** “任务”Tab 的分段：传输列表（P5）与后台任务（P7 工作流） */
private enum class TasksSegment(val label: Int) {
    Transfers(R.string.tasks_segment_transfers),
    Background(R.string.tasks_segment_background),
}

/**
 * “任务”Tab：顶部分段切换“传输”与“后台任务”。
 * 传输列表按上传/下载分组，支持暂停/继续/取消/重试、40004 重命名或覆盖、清除已完成、打开/分享已下载文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksTab(accountId: String) {
    val context = LocalContext.current
    val vm: TransferListViewModel = koinViewModel(key = "transfers:$accountId") { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var segment by rememberSaveable { mutableIntStateOf(TasksSegment.Transfers.ordinal) }
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is TransferListEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_tasks)) },
                actions = {
                    if (segment == TasksSegment.Transfers.ordinal && state.hasFinished) {
                        IconButton(onClick = { vm.on(TransferListIntent.ClearFinished) }) {
                            Icon(Icons.Outlined.CleaningServices, contentDescription = stringResource(R.string.transfer_clear_finished))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TasksSegment.entries.forEach { item ->
                    SegmentedButton(
                        selected = segment == item.ordinal,
                        onClick = { segment = item.ordinal },
                        shape = SegmentedButtonDefaults.itemShape(item.ordinal, TasksSegment.entries.size),
                    ) { Text(stringResource(item.label)) }
                }
            }
            when (TasksSegment.entries[segment]) {
                TasksSegment.Transfers -> TransferList(state, vm::on)
                TasksSegment.Background -> BackgroundTasks(accountId, snackbar)
            }
        }
    }
}

@Composable
private fun TransferList(state: TransferListState, onIntent: (TransferListIntent) -> Unit) {
    if (state.loaded && state.isEmpty) {
        EmptyMessage(Icons.Outlined.SwapVert, R.string.transfer_empty, R.string.transfer_empty_hint)
        return
    }
    var renaming by remember { mutableStateOf<TransferItem?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (state.uploads.isNotEmpty()) {
            item(key = "h:upload") { SectionHeader(stringResource(R.string.transfer_section_uploads, state.uploads.size)) }
            items(state.uploads, key = TransferRow::id) { row ->
                TransferRowItem(row, row.id in state.busyIds, onIntent, onRename = { renaming = row.item })
            }
        }
        if (state.downloads.isNotEmpty()) {
            item(key = "h:download") { SectionHeader(stringResource(R.string.transfer_section_downloads, state.downloads.size)) }
            items(state.downloads, key = TransferRow::id) { row ->
                TransferRowItem(row, row.id in state.busyIds, onIntent, onRename = {})
            }
        }
    }
    renaming?.let { item ->
        RenameDialog(
            initial = suggestName(item.displayName),
            onConfirm = { name ->
                onIntent(TransferListIntent.RetryWithRename(item.id, name))
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun TransferRowItem(
    row: TransferRow,
    busy: Boolean,
    onIntent: (TransferListIntent) -> Unit,
    onRename: () -> Unit,
) {
    val context = LocalContext.current
    val item = row.item
    val completedDownload = item.kind == TransferKind.DOWNLOAD && item.state == TransferState.COMPLETED && item.destPath != null
    val failed = item.state == TransferState.FAILED
    ListItem(
        modifier = if (completedDownload) Modifier.clickable { openDownload(context, item, share = false) } else Modifier,
        leadingContent = {
            Icon(
                when {
                    failed -> Icons.Outlined.ErrorOutline
                    item.state == TransferState.COMPLETED -> Icons.Outlined.CheckCircle
                    item.kind == TransferKind.UPLOAD -> Icons.Outlined.CloudUpload
                    else -> Icons.Outlined.CloudDownload
                },
                contentDescription = null,
                tint = when {
                    failed -> MaterialTheme.colorScheme.error
                    item.state == TransferState.COMPLETED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        headlineContent = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(locationText(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                when (item.state) {
                    TransferState.RUNNING, TransferState.PAUSED ->
                        if (item.size > 0) {
                            LinearProgressIndicator(progress = { item.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    TransferState.QUEUED, TransferState.PREPARING, TransferState.FINISHING ->
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
                Text(
                    statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.needsConflictResolution) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRename, enabled = !busy) { Text(stringResource(R.string.transfer_conflict_rename)) }
                        TextButton(onClick = { onIntent(TransferListIntent.RetryWithOverwrite(item.id)) }, enabled = !busy) {
                            Text(stringResource(R.string.transfer_conflict_overwrite))
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    row.canPause -> IconButton(onClick = { onIntent(TransferListIntent.Pause(item.id)) }, enabled = !busy) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.transfer_action_pause))
                    }
                    row.canResume -> IconButton(onClick = { onIntent(TransferListIntent.Resume(item.id)) }, enabled = !busy) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.transfer_action_resume))
                    }
                    row.canRetry -> IconButton(onClick = { onIntent(TransferListIntent.Retry(item.id)) }, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                    completedDownload -> IconButton(onClick = { openDownload(context, item, share = false) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.action_open))
                    }
                }
                RowMenu(row, completedDownload, busy, onIntent)
            }
        },
    )
}

@Composable
private fun RowMenu(row: TransferRow, completedDownload: Boolean, busy: Boolean, onIntent: (TransferListIntent) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.transfer_more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (completedDownload) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = {
                    open = false
                    openDownload(context, row.item, share = false)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                    open = false
                    openDownload(context, row.item, share = true)
                })
            }
            if (row.canCancel) {
                DropdownMenuItem(text = { Text(stringResource(R.string.transfer_action_cancel)) }, onClick = {
                    open = false
                    onIntent(TransferListIntent.Cancel(row.id))
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.transfer_action_remove)) }, onClick = {
                open = false
                onIntent(TransferListIntent.Remove(row.id))
            })
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.transfer_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EmptyMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, hint: Int?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Text(
                stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            hint?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/** 冲突时建议的新名称：“name (1).ext” */
private fun suggestName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} (1)${name.substring(dot)}" else "$name (1)"
}

/** 用其它应用打开或分享已下载的文件 */
private fun openDownload(context: Context, item: TransferItem, share: Boolean) {
    val dest = item.destPath ?: return
    val uri = DownloadTargets.shareUri(context, dest)
    val mime = DownloadTargets.mimeType(item.displayName)
    val intent = if (share) {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.transfer_open_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.transfer_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Android 13+：首次出现活动传输时请求通知权限（进度通知与完成提示）。
 * 拒绝后不再追问（本次进程内）；传输本身不依赖该权限。
 */
@Composable
fun RequestNotificationPermission(hasActiveTransfers: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(hasActiveTransfers) {
        if (hasActiveTransfers && !asked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
