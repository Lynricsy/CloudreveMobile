package plus.ling.opencloudreve.ui.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.DownloadFileSelection
import plus.ling.opencloudreve.core.model.TaskCategory
import plus.ling.opencloudreve.core.model.TaskStatus
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowDetail
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowListEffect
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowListIntent
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowListState
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowListViewModel
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowTaskRow
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.CenteredMessage
import plus.ling.opencloudreve.ui.files.FullError
import plus.ling.opencloudreve.ui.files.InfoLine
import plus.ling.opencloudreve.ui.files.SectionLabel
import plus.ling.opencloudreve.ui.files.displayPath
import plus.ling.opencloudreve.ui.files.formatBytes
import plus.ling.opencloudreve.ui.files.formatDateTime

/**
 * “任务”Tab 的“后台任务”段：常规/下载中/已完成三类工作流任务；下载中前台 3 秒轮询；
 * 点按打开详情（阶段时间线、进度、错误、节点、远程下载文件选择/取消）；右下角新建远程下载。
 */
@Composable
fun BackgroundTasks(accountId: String, snackbar: SnackbarHostState) {
    val vm: WorkflowListViewModel = koinViewModel(key = "workflow:$accountId") { parametersOf(accountId, TaskCategory.GENERAL) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var creating by rememberSaveable { mutableStateOf(false) }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is WorkflowListEffect.Canceled -> snackbar.showSnackbar(resources.getString(R.string.task_canceled))
            is WorkflowListEffect.FilesSelected -> snackbar.showSnackbar(resources.getString(R.string.task_files_selected))
            is WorkflowListEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }
    // 页面可见时轮询，离开（后台/切换 Tab）时停止
    LifecycleResumeEffect(vm) {
        vm.on(WorkflowListIntent.SetForeground(true))
        onPauseOrDispose { vm.on(WorkflowListIntent.SetForeground(false)) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkflowListState.CATEGORIES.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { vm.on(WorkflowListIntent.SetCategory(category)) },
                        label = { Text(stringResource(categoryLabel(category))) },
                    )
                }
            }
            val error = state.error
            when {
                state.loading && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && state.rows.isEmpty() -> FullError(error) { vm.on(WorkflowListIntent.Refresh) }
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.on(WorkflowListIntent.Refresh) }) {
                    if (state.isEmpty) {
                        CenteredMessage(Icons.Outlined.PendingActions, stringResource(R.string.tasks_background_placeholder))
                    } else {
                        TaskList(state, vm::on)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.remote_download_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    state.detail?.let { detail ->
        TaskDetailSheet(detail, busy = detail.row.id in state.busyIds, onIntent = vm::on)
    }
    if (creating) {
        RemoteDownloadDialog(
            accountId = accountId,
            dstUri = CrUri.my().toString(),
            torrentUri = null,
            onDismiss = { creating = false },
            onCreated = {
                creating = false
                // 已在“下载中”时 SetCategory 不会重新加载（且列表为空时轮询已停止），需显式刷新
                if (state.category == TaskCategory.DOWNLOADING) vm.on(WorkflowListIntent.Refresh)
                else vm.on(WorkflowListIntent.SetCategory(TaskCategory.DOWNLOADING))
            },
        )
    }
}

private fun categoryLabel(category: String): Int = when (category) {
    TaskCategory.DOWNLOADING -> R.string.task_category_downloading
    TaskCategory.DOWNLOADED -> R.string.task_category_downloaded
    else -> R.string.task_category_general
}

@Composable
private fun TaskList(state: WorkflowListState, onIntent: (WorkflowListIntent) -> Unit) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.rows.size) {
        if (nearEnd && state.hasMore && !state.loadingMore) onIntent(WorkflowListIntent.LoadMore)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.rows, key = WorkflowTaskRow::id) { row ->
            TaskRowItem(row, onClick = { onIntent(WorkflowListIntent.OpenDetail(row.id)) })
        }
        if (state.loadingMore) {
            item(key = "more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
    }
}

@Composable
private fun TaskRowItem(row: WorkflowTaskRow, onClick: () -> Unit) {
    val download = row.download
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(taskTypeIcon(row.type), contentDescription = stringResource(taskTypeLabel(row.type))) },
        headlineContent = { Text(taskTitle(row), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(taskSubtitle(row), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val progress = row.downloadProgress
                if (row.isActive && download != null && progress != null) {
                    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else if (row.status == TaskStatus.PROCESSING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
    )
}

/** 标题：远程下载用下载器给出的名称，其余用源文件名 */
@Composable
private fun taskTitle(row: WorkflowTaskRow): String {
    row.download?.name?.takeIf { it.isNotEmpty() }?.let { return it }
    val source = row.sources.firstOrNull()?.let { CrUri.parseOrNull(it)?.name()?.ifEmpty { null } ?: it }
    val label = stringResource(taskTypeLabel(row.type))
    return if (source != null) "$label · $source" else label
}

@Composable
private fun taskSubtitle(row: WorkflowTaskRow): String {
    val context = LocalContext.current
    val download = row.download
    return buildList {
        add(stringResource(taskStatusLabel(row.status)))
        if (download != null && download.total > 0) {
            add("${formatBytes(context, download.downloaded)} / ${formatBytes(context, download.total)}")
            if (row.isActive && download.downloadSpeed > 0) add(stringResource(R.string.task_speed, formatBytes(context, download.downloadSpeed)))
        }
        if (row.failedCount > 0) add(stringResource(R.string.task_failed_count, row.failedCount))
        row.task.createdAt?.let { add(formatDateTime(it)) }
    }.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(detail: WorkflowDetail, busy: Boolean, onIntent: (WorkflowListIntent) -> Unit) {
    val row = detail.row
    val task = row.task
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { onIntent(WorkflowListIntent.CloseDetail) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(taskTypeIcon(row.type), contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(taskTitle(row), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Text(stringResource(taskStatusLabel(row.status)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detail.polling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            // 阶段时间线
            val steps = taskSteps(task)
            if (steps.isNotEmpty()) {
                SectionLabel(stringResource(R.string.task_phase))
                val active = activeStep(task, steps)
                steps.forEachIndexed { index, step ->
                    val done = index < active
                    val current = index == active && row.status != TaskStatus.COMPLETED
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            current && row.status == TaskStatus.ERROR -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            current -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            else -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        }
                        Text(
                            stringResource(step.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                row.phase?.let { InfoLine(stringResource(R.string.task_phase), it) }
            }

            // 进度（progress/:id）
            if (detail.progress.isNotEmpty()) {
                SectionLabel(stringResource(R.string.task_progress))
                detail.progress.values.forEach { p ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "${p.identifier.ifEmpty { "-" }}  ${formatBytes(context, p.current)} / ${formatBytes(context, p.total)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (p.total > 0) LinearProgressIndicator(progress = { (p.current.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            SectionLabel(stringResource(R.string.info_basic))
            InfoLine(stringResource(R.string.task_type), stringResource(taskTypeLabel(row.type)))
            row.sources.forEach { src -> InfoLine(stringResource(R.string.task_source), uriText(context, src)) }
            row.destination?.let { InfoLine(stringResource(R.string.task_destination), uriText(context, it)) }
            row.dstPolicyId?.let { InfoLine(stringResource(R.string.task_dst_policy), it) }
            task.node?.let { InfoLine(stringResource(R.string.task_node), it.name.ifEmpty { it.id }) }
            task.createdAt?.let { InfoLine(stringResource(R.string.info_created), formatDateTime(it)) }
            task.updatedAt?.let { InfoLine(stringResource(R.string.info_updated_at), formatDateTime(it)) }
            if (task.duration > 0) InfoLine(stringResource(R.string.task_duration), formatDuration(task.duration))
            if (task.retryCount > 0) InfoLine(stringResource(R.string.task_retry_count), task.retryCount.toString())
            task.error?.takeIf { it.isNotEmpty() }?.let { err ->
                SectionLabel(stringResource(R.string.task_error))
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            }
            task.errorHistory.takeIf { it.isNotEmpty() }?.let { history ->
                SectionLabel(stringResource(R.string.task_error_history))
                history.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            }

            // 远程下载：文件列表（可选择）
            val download = row.download
            if (download != null) {
                SectionLabel(stringResource(R.string.task_download_status))
                if (download.total > 0) {
                    InfoLine(stringResource(R.string.task_downloaded), "${formatBytes(context, download.downloaded)} / ${formatBytes(context, download.total)}")
                }
                if (download.downloadSpeed > 0) InfoLine(stringResource(R.string.task_download_speed), stringResource(R.string.task_speed, formatBytes(context, download.downloadSpeed)))
                download.errorMessage?.takeIf { it.isNotEmpty() }?.let { InfoLine(stringResource(R.string.task_error), it) }
                if (download.files.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.task_download_files))
                    val selection = remember(row.id, download.files) {
                        mutableStateMapOf<Int, Boolean>().apply { download.files.forEach { put(it.index, it.selected) } }
                    }
                    download.files.forEach { file ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selection[file.index] == true,
                                onCheckedChange = { selection[file.index] = it },
                                enabled = row.canSelectFiles && !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                                Text(
                                    "${formatBytes(context, file.size)} · ${(file.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (row.canSelectFiles) {
                        val changed = download.files.any { selection[it.index] != it.selected }
                        TextButton(
                            onClick = {
                                onIntent(WorkflowListIntent.SelectFiles(row.id, download.files.map { DownloadFileSelection(it.index, selection[it.index] == true) }))
                            },
                            enabled = changed && !busy && selection.values.any { it },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(R.string.task_apply_selection)) }
                    }
                }
            }

            if (row.canCancel) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(
                    onClick = { onIntent(WorkflowListIntent.Cancel(row.id)) },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) { Text(stringResource(R.string.task_cancel), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** URI 显示为可读路径；非 cloudreve URI（下载链接）原样 */
private fun uriText(context: android.content.Context, raw: String): String =
    CrUri.parseOrNull(raw)?.let { displayPath(context, it) } ?: raw

/** 毫秒 → h:mm:ss */
private fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
