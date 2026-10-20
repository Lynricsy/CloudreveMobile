package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminConditions
import plus.ling.opencloudreve.core.model.AdminNode
import plus.ling.opencloudreve.core.presentation.admin.AdminListEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminNodesViewModel

/** 节点列表（只读，不支持删除）：详情含关联策略与脱敏设置，可做连通测试 */
@Composable
internal fun AdminNodesScreen(accountId: String, onBack: () -> Unit) {
    val vm: AdminNodesViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    CollectAdminListEffects(vm.effects, snackbar) { effect ->
        if (effect is AdminListEffect.NodeTestResult) {
            scope.launch {
                val text = when {
                    effect.success -> resources.getString(R.string.admin_node_test_ok)
                    effect.message.isNullOrBlank() -> resources.getString(R.string.admin_node_test_failed_plain)
                    else -> resources.getString(R.string.admin_node_test_failed, effect.message)
                }
                snackbar.showSnackbar(text)
            }
        }
    }

    val statusOptions = listOf(NODE_STATUS_ACTIVE, NODE_STATUS_SUSPENDED).map { it to nodeStatusLabel(it) }
    AdminListScaffold(
        title = stringResource(R.string.admin_nodes),
        state = state,
        onIntent = vm::on,
        itemKey = { it.id },
        supportsDelete = vm.supportsDelete,
        snackbar = snackbar,
        onBack = onBack,
        onItemClick = { vm.on(AdminListIntent.OpenDetail(it.id)) },
        filters = {
            OptionFilterChip(stringResource(R.string.admin_node_status), state.conditions[AdminConditions.NODE_STATUS], statusOptions) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.NODE_STATUS, it))
            }
        },
        detailSheet = { node ->
            NodeDetail(node, busy = state.busyId == node.id, onTest = { vm.on(AdminListIntent.TestNode(node.id)) }) {
                // 详情打开时 VM 交接到该节点的网页详情页
                OpenInWebAction(
                    handoff = state.handoff,
                    opening = state.openingWeb,
                    serverVersion = null,
                    snackbar = snackbar,
                    onOpen = { vm.on(AdminListIntent.OpenInWeb) },
                )
            }
        },
    ) { node ->
        AdminListItem(
            headline = node.name,
            overline = nodeTypeLabel(node.type),
            supporting = listOf(
                node.server.ifEmpty { EMPTY_VALUE },
                stringResource(R.string.admin_node_weight_value, node.weight),
            ).joinToString("\n"),
            trailing = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(nodeStatusLabel(node.status), if (node.status == NODE_STATUS_ACTIVE) ChipTone.Positive else ChipTone.Neutral)
                    if (state.busyId == node.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            },
        )
    }
}

@Composable
private fun NodeDetail(node: AdminNode, busy: Boolean, onTest: () -> Unit, webAction: @Composable () -> Unit) {
    SectionTitle(node.name)
    InfoRow(stringResource(R.string.admin_field_id), node.id.toString())
    InfoRow(stringResource(R.string.admin_node_type), nodeTypeLabel(node.type))
    InfoRow(stringResource(R.string.admin_node_status), nodeStatusLabel(node.status))
    InfoRow(stringResource(R.string.admin_node_server), node.server)
    InfoRow(stringResource(R.string.admin_order_weight), node.weight.toString())
    InfoRow(stringResource(R.string.admin_field_created), formatTime(node.createdAt))
    InfoRow(stringResource(R.string.admin_field_updated), formatTime(node.updatedAt))

    SheetActions {
        webAction()
        OutlinedButton(enabled = !busy, onClick = onTest) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            }
            Text(stringResource(R.string.admin_node_test), Modifier.padding(start = 8.dp))
        }
    }

    SectionTitle(stringResource(R.string.admin_node_policies))
    if (node.edges.storagePolicy.isEmpty()) {
        Text(
            EMPTY_VALUE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        node.edges.storagePolicy.forEach { p -> InfoRow("#${p.id} · ${p.type}", p.name) }
    }

    node.settings?.let { settings ->
        val redacted = remember(settings) { prettyJson(redactSecrets(settings)) }
        CollapsibleJson(stringResource(R.string.admin_node_settings), redacted)
    }
}
