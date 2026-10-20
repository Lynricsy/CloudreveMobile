package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminConditions
import plus.ling.opencloudreve.core.model.AdminTask
import plus.ling.opencloudreve.core.model.QueueMetric
import plus.ling.opencloudreve.core.presentation.admin.AdminListEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminQueueViewModel

/** 清理选项：早于 N 天 */
private val CLEANUP_DAYS = listOf(1, 7, 30)
private const val SECONDS_PER_DAY = 86_400L

/** 任务队列：队列指标、按类型/状态/用户筛选、详情（含内部状态 JSON）、清理旧任务 */
@Composable
internal fun AdminQueueScreen(accountId: String, onBack: () -> Unit) {
    val vm: AdminQueueViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var cleanup by rememberSaveable { mutableStateOf(false) }
    val cleaning = state.busyId == AdminQueueViewModel.CLEANUP_BUSY_ID

    CollectAdminListEffects(vm.effects, snackbar) { effect ->
        if (effect is AdminListEffect.TasksCleaned) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.admin_tasks_cleaned)) }
        }
    }

    val statusOptions = TASK_STATUSES.map { it to taskStatusLabel(it) }
    val typeOptions = TASK_TYPES.map { it to taskTypeLabel(it) }
    AdminListScaffold(
        title = stringResource(R.string.admin_queue),
        state = state,
        onIntent = vm::on,
        itemKey = { it.id },
        supportsDelete = vm.supportsDelete,
        snackbar = snackbar,
        onBack = onBack,
        onItemClick = { vm.on(AdminListIntent.OpenDetail(it.id)) },
        header = {
            MetricsRow(metrics, onRefresh = { vm.on(AdminListIntent.RefreshMetrics) })
            if (cleaning) LinearProgressIndicator(Modifier.fillMaxWidth())
        },
        menu = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.admin_cleanup)) },
                leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                enabled = !cleaning,
                onClick = {
                    dismiss()
                    cleanup = true
                },
            )
        },
        filters = {
            val c = state.conditions
            OptionFilterChip(stringResource(R.string.admin_task_type), c[AdminConditions.TASK_TYPE], typeOptions) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.TASK_TYPE, it))
            }
            OptionFilterChip(stringResource(R.string.admin_task_status), c[AdminConditions.TASK_STATUS], statusOptions) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.TASK_STATUS, it))
            }
            TextFilterChip(stringResource(R.string.admin_filter_user_id), c[AdminConditions.TASK_USER_ID], numeric = true) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.TASK_USER_ID, it))
            }
        },
        detailSheet = { task -> TaskDetail(task) },
    ) { task ->
        val brief = task.publicState?.error?.ifEmpty { null } ?: summaryBrief(task)
        AdminListItem(
            overline = "#${task.id} · ${userOf(task)}",
            headline = taskTypeLabel(task.type),
            supporting = listOfNotNull(formatTime(task.createdAt), brief).joinToString("\n"),
            trailing = { StatusChip(taskStatusLabel(task.status), taskStatusTone(task.status)) },
        )
    }

    if (cleanup) {
        CleanupDialog(
            onDismiss = { cleanup = false },
            onConfirm = { days ->
                cleanup = false
                val notAfter = System.currentTimeMillis() / 1000 - days * SECONDS_PER_DAY
                vm.on(AdminListIntent.CleanupTasks(notAfter))
            },
        )
    }
}

private fun userOf(task: AdminTask): String =
    task.edges.user?.let { it.nick.ifEmpty { it.email } } ?: if (task.userTasks != 0) "#${task.userTasks}" else EMPTY_VALUE

/** 摘要简述：阶段 + 源/目标 */
private fun summaryBrief(task: AdminTask): String? {
    val s = task.summary ?: return null
    val src = s.string("src_str") ?: s.string("src") ?: s.strings("src_multiple").firstOrNull()
    val dst = s.string("dst")
    val path = when {
        src != null && dst != null -> "$src → $dst"
        else -> src ?: dst
    }
    return listOfNotNull(s.phase?.ifEmpty { null }, path).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun MetricsRow(metrics: List<QueueMetric>, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.admin_queue_metrics),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.admin_refresh_metrics))
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.forEach { m ->
            Card(Modifier.width(168.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(queueNameLabel(m.name), style = MaterialTheme.typography.titleSmall)
                    MetricLine(stringResource(R.string.admin_metric_busy), m.busyWorkers)
                    MetricLine(stringResource(R.string.admin_metric_submitted), m.submittedTasks)
                    MetricLine(stringResource(R.string.admin_metric_success), m.successTasks)
                    MetricLine(stringResource(R.string.admin_metric_failure), m.failureTasks)
                    MetricLine(stringResource(R.string.admin_metric_suspending), m.suspendingTasks)
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value.toString(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TaskDetail(task: AdminTask) {
    SectionTitle(taskTypeLabel(task.type))
    InfoRow(stringResource(R.string.admin_field_id), task.id.toString())
    InfoRow(stringResource(R.string.admin_task_type), task.type)
    InfoRow(stringResource(R.string.admin_task_status), taskStatusLabel(task.status))
    InfoRow(stringResource(R.string.admin_task_user), userOf(task))
    task.correlationId?.takeIf { it.isNotEmpty() }?.let { InfoRow(stringResource(R.string.admin_task_correlation), it) }
    task.node?.let { InfoRow(stringResource(R.string.admin_task_node), "${it.name} (#${it.id})") }
    InfoRow(stringResource(R.string.admin_field_created), formatTime(task.createdAt))
    InfoRow(stringResource(R.string.admin_field_updated), formatTime(task.updatedAt))

    task.summary?.let { s ->
        SectionTitle(stringResource(R.string.admin_task_summary))
        s.phase?.takeIf { it.isNotEmpty() }?.let { InfoRow(stringResource(R.string.admin_task_phase), it) }
        summaryBrief(task)?.let { InfoRow(stringResource(R.string.admin_task_summary), it) }
        s.props?.let { CollapsibleJson(stringResource(R.string.admin_task_summary_props), prettyJson(it)) }
    }

    task.publicState?.let { p ->
        SectionTitle(stringResource(R.string.admin_task_public_state))
        InfoRow(stringResource(R.string.admin_task_retry_count), p.retryCount.toString())
        InfoRow(stringResource(R.string.admin_task_duration), formatDurationNanos(p.executedDuration))
        if (p.resumeTime > 0) {
            InfoRow(stringResource(R.string.admin_task_resume_time), formatTime(kotlin.time.Instant.fromEpochSeconds(p.resumeTime)))
        }
        p.error?.takeIf { it.isNotEmpty() }?.let { InfoRow(stringResource(R.string.admin_task_error), it) }
        if (p.errorHistory.isNotEmpty()) {
            InfoRow(stringResource(R.string.admin_task_error_history), p.errorHistory.joinToString("\n"))
        }
        p.slaveTaskProps?.let { CollapsibleJson(stringResource(R.string.admin_task_slave_props), prettyJson(redactSecrets(it))) }
    }

    task.privateState?.takeIf { it.isNotEmpty() }?.let { raw ->
        val pretty = remember(raw) { prettyJson(raw) }
        CollapsibleJson(stringResource(R.string.admin_task_private_state), pretty)
    }
}

@Composable
private fun CleanupDialog(onDismiss: () -> Unit, onConfirm: (days: Int) -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(CLEANUP_DAYS[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_cleanup)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.admin_cleanup_message), style = MaterialTheme.typography.bodyMedium)
                CLEANUP_DAYS.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = days == d, onClick = { days = d }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == d, onClick = null)
                        Text(pluralStringResource(R.plurals.admin_cleanup_older_than, d, d), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(days) }) { Text(stringResource(R.string.admin_cleanup_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
