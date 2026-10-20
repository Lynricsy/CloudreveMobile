package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminConditions
import plus.ling.opencloudreve.core.model.AdminFile
import plus.ling.opencloudreve.core.presentation.admin.AdminListEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminFilesViewModel
import plus.ling.opencloudreve.ui.common.openCustomTab

/** 条件真值 */
private const val TRUE = "true"

/** 文件列表：按文件名/用户/策略/已分享/有直链筛选；详情含实体、直链、元数据，可获取下载地址 */
@Composable
internal fun AdminFilesScreen(accountId: String, onBack: () -> Unit) {
    val vm: AdminFilesViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val copy = rememberAdminCopy(snackbar)
    val context = LocalContext.current
    var fileUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }

    CollectAdminListEffects(vm.effects, snackbar) { effect ->
        if (effect is AdminListEffect.FileUrlReady) fileUrl = effect.url
    }

    AdminListScaffold(
        title = stringResource(R.string.admin_files),
        state = state,
        onIntent = vm::on,
        itemKey = { it.id },
        supportsDelete = vm.supportsDelete,
        snackbar = snackbar,
        onBack = onBack,
        onItemClick = { vm.on(AdminListIntent.OpenDetail(it.id)) },
        filters = {
            val c = state.conditions
            TextFilterChip(stringResource(R.string.admin_file_name), c[AdminConditions.FILE_NAME]) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.FILE_NAME, it))
            }
            TextFilterChip(stringResource(R.string.admin_filter_user_id), c[AdminConditions.FILE_USER], numeric = true) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.FILE_USER, it))
            }
            TextFilterChip(stringResource(R.string.admin_filter_policy_id), c[AdminConditions.FILE_POLICY], numeric = true) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.FILE_POLICY, it))
            }
            ToggleFilterChip(stringResource(R.string.admin_filter_shared), c[AdminConditions.FILE_SHARED] == TRUE) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.FILE_SHARED, if (it) TRUE else null))
            }
            ToggleFilterChip(stringResource(R.string.admin_filter_direct_link), c[AdminConditions.FILE_DIRECT_LINK] == TRUE) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.FILE_DIRECT_LINK, if (it) TRUE else null))
            }
        },
        detailSheet = { file ->
            FileDetail(
                file = file,
                busy = state.busyId == file.id,
                onCopy = copy,
                onFetchUrl = { vm.on(AdminListIntent.FetchFileUrl(file.id)) },
                onDelete = { confirmDelete = file.id },
            )
        },
    ) { file ->
        AdminListItem(
            headline = file.name,
            supporting = "${formatBytes(file.size)} · ${ownerOf(file)} · ${formatTime(file.updatedAt ?: file.createdAt)}",
            trailing = if (state.busyId == file.id) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
    }

    fileUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { fileUrl = null },
            title = { Text(stringResource(R.string.admin_file_url_title)) },
            text = {
                Column {
                    SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        stringResource(R.string.admin_file_url_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fileUrl = null
                    openCustomTab(context, url)
                }) { Text(stringResource(R.string.admin_open)) }
            },
            dismissButton = {
                TextButton(onClick = { copy(url) }) { Text(stringResource(R.string.admin_copy)) }
            },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.admin_file_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.on(AdminListIntent.Delete(listOf(id)))
                }) { Text(stringResource(R.string.admin_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun ownerOf(file: AdminFile): String =
    file.edges.owner?.let { it.nick.ifEmpty { it.email } } ?: "#${file.ownerId}"

@Composable
private fun FileDetail(
    file: AdminFile,
    busy: Boolean,
    onCopy: (String) -> Unit,
    onFetchUrl: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionTitle(file.name)
    InfoRow(stringResource(R.string.admin_field_id), file.id.toString())
    InfoRow(stringResource(R.string.admin_file_size), formatBytes(file.size))
    InfoRow(stringResource(R.string.admin_file_owner), "${ownerOf(file)} (#${file.ownerId})")
    InfoRow(
        stringResource(R.string.admin_file_policy),
        file.edges.storagePolicies?.let { "${it.name} (#${it.id})" } ?: "#${file.storagePolicyFiles}",
    )
    InfoRow(stringResource(R.string.admin_file_primary_entity), file.primaryEntity.toString())
    InfoRow(stringResource(R.string.admin_file_shares), file.edges.shares.size.toString())
    file.fileHashId?.let { InfoRow(stringResource(R.string.admin_field_hash_id), it) }
    InfoRow(stringResource(R.string.admin_field_created), formatTime(file.createdAt))
    InfoRow(stringResource(R.string.admin_field_updated), formatTime(file.updatedAt))

    SheetActions {
        OutlinedButton(enabled = !busy, onClick = onFetchUrl) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Link, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            }
            Text(stringResource(R.string.admin_file_get_url), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(ButtonDefaults.IconSize), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.admin_delete), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
        }
    }

    if (file.edges.entities.isNotEmpty()) {
        SectionTitle(stringResource(R.string.admin_file_entities))
        file.edges.entities.forEach { e ->
            ListItem(
                overlineContent = { Text("#${e.id} · ${entityTypeLabel(e.type)}") },
                headlineContent = { Text(formatBytes(e.size)) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            e.edges.storagePolicy?.name,
                            stringResource(R.string.admin_entity_refs, e.referenceCount),
                            formatTime(e.createdAt),
                            e.source.ifEmpty { null },
                        ).joinToString("\n"),
                    )
                },
            )
        }
    }

    if (file.directLinkMap.isNotEmpty()) {
        SectionTitle(stringResource(R.string.admin_file_direct_links))
        file.directLinkMap.forEach { (id, url) ->
            val link = file.edges.directLinks.firstOrNull { it.id.toString() == id }
            val label = link?.let { stringResource(R.string.admin_direct_link_label, it.name.ifEmpty { "#$id" }, it.downloads) } ?: "#$id"
            InfoRow(label, url, onCopy = onCopy)
        }
    }

    if (file.edges.metadata.isNotEmpty()) {
        SectionTitle(stringResource(R.string.admin_file_metadata))
        Column(Modifier.fillMaxWidth()) {
            file.edges.metadata.forEach { m -> InfoRow(m.name, m.value) }
        }
    }
}
