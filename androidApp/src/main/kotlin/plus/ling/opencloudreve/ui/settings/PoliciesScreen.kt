package plus.ling.opencloudreve.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.presentation.settings.PoliciesIntent
import plus.ling.opencloudreve.core.presentation.settings.PoliciesState
import plus.ling.opencloudreve.core.presentation.settings.PolicyItem
import plus.ling.opencloudreve.ui.files.formatBytes
import kotlin.math.roundToInt

/** 可用存储策略（只读）：默认策略在首位；负载均衡策略展开子策略与权重占比 */
@Composable
fun PoliciesScreen(state: PoliciesState, onIntent: (PoliciesIntent) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { SmallTopBar(stringResource(R.string.policies_title), onBack) }) { padding ->
        RefreshableColumn(
            refreshing = state.loading && state.items.isNotEmpty(),
            onRefresh = { onIntent(PoliciesIntent.Refresh) },
            padding = padding,
        ) {
            state.error?.let { LoadError(it) { onIntent(PoliciesIntent.Refresh) } }
            if (!state.loading && state.error == null && state.items.isEmpty()) {
                Text(
                    stringResource(R.string.policies_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            state.items.forEach { PolicyCard(it) }
        }
    }
}

@Composable
private fun PolicyCard(item: PolicyItem) {
    val context = LocalContext.current
    val p = item.policy
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (item.isLoadBalance) Icons.Outlined.AccountTree else Icons.Outlined.Storage, contentDescription = null)
                Text(p.name.ifBlank { p.id }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (item.isDefault) AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.policies_default)) })
            }
            Text(policyTypeLabel(p.type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val maxSize = if (p.maxSize > 0) formatBytes(context, p.maxSize) else stringResource(R.string.policies_unlimited)
            Text(stringResource(R.string.policies_max_size, maxSize), style = MaterialTheme.typography.bodySmall)
            if (p.encryption) Text(stringResource(R.string.policies_encrypted), style = MaterialTheme.typography.bodySmall)
            if (item.isLoadBalance && item.children.isNotEmpty()) {
                Text(
                    stringResource(R.string.policies_children),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                item.children.forEach { child ->
                    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${child.policy.name.ifBlank { child.policy.id }} · ${policyTypeLabel(child.policy.type)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.policies_weight, child.weight, (child.share * 100).roundToInt()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { child.share.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun policyTypeLabel(type: String): String = when (type) {
    StoragePolicy.TYPE_LOCAL -> stringResource(R.string.policies_type_local)
    StoragePolicy.TYPE_REMOTE -> stringResource(R.string.policies_type_remote)
    StoragePolicy.TYPE_LOAD_BALANCE -> stringResource(R.string.policies_type_lb)
    StoragePolicy.TYPE_ONEDRIVE -> "OneDrive"
    StoragePolicy.TYPE_S3 -> "S3"
    StoragePolicy.TYPE_OSS -> stringResource(R.string.policies_type_oss)
    StoragePolicy.TYPE_COS -> stringResource(R.string.policies_type_cos)
    StoragePolicy.TYPE_QINIU -> stringResource(R.string.policies_type_qiniu)
    StoragePolicy.TYPE_UPYUN -> stringResource(R.string.policies_type_upyun)
    StoragePolicy.TYPE_KS3 -> stringResource(R.string.policies_type_ks3)
    StoragePolicy.TYPE_OBS -> stringResource(R.string.policies_type_obs)
    else -> type
}
