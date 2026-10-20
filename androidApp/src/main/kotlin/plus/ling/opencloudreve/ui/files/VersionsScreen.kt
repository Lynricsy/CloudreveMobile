package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.files.VersionRow
import plus.ling.opencloudreve.core.presentation.files.VersionsEffect
import plus.ling.opencloudreve.core.presentation.files.VersionsIntent
import plus.ling.opencloudreve.core.presentation.files.VersionsViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/** 版本管理页目的地；[uri] 为链接目标 */
@Serializable
data class VersionsRoute(val uri: String)

/** 标签与元数据编辑页目的地；[uris] 为所选文件 */
@Serializable
data class MetadataEditRoute(val uris: List<String>)

/** P7 文件菜单动作：版本、标签与元数据（接入 `InstalledFileFeatures`） */
internal object FileToolHooks {
    val versions = FileHook { context, files ->
        val file = files.singleOrNull() ?: return@FileHook
        context.navController.navigate(VersionsRoute(file.linkedUri))
    }

    val metadata = FileHook { context, files ->
        if (files.isEmpty()) return@FileHook
        context.navController.navigate(MetadataEditRoute(files.map { it.path }))
    }
}

/**
 * 文件版本：新到旧列出（大小、时间、策略、创建者），设为当前、删除、预览此版本。
 * @param onPreview 预览指定版本（文件条目 + 实体 id）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(accountId: String, uri: String, onClose: () -> Unit, onPreview: (FileItem, String) -> Unit) {
    val vm: VersionsViewModel = koinViewModel(key = "versions:$uri") { parametersOf(accountId, uri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var confirmDelete by remember { mutableStateOf<VersionRow?>(null) }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            VersionsEffect.Updated -> snackbar.showSnackbar(resources.getString(R.string.info_updated))
            is VersionsEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.action_versions))
                        Text(state.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.on(VersionsIntent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = state.error
            when {
                state.loading && state.versions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && state.versions.isEmpty() -> FullError(error) { vm.on(VersionsIntent.Refresh) }
                state.versions.isEmpty() -> CenteredMessage(Icons.Outlined.History, stringResource(R.string.versions_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.versions, key = VersionRow::id) { row ->
                        VersionItem(
                            row = row,
                            busy = state.mutatingId == row.id,
                            onPreview = { state.file?.let { onPreview(it, row.id) } },
                            onSetCurrent = { vm.on(VersionsIntent.SetCurrent(row.id)) },
                            onDelete = { confirmDelete = row },
                        )
                    }
                }
            }
            if (state.mutatingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.versions_delete_title)) },
            text = { Text(stringResource(R.string.versions_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.on(VersionsIntent.Delete(row.id))
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun VersionItem(row: VersionRow, busy: Boolean, onPreview: () -> Unit, onSetCurrent: () -> Unit, onDelete: () -> Unit) {
    val e = row.entity
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onPreview),
        leadingContent = {
            Icon(
                if (row.isCurrent) Icons.Filled.CheckCircle else Icons.Filled.History,
                contentDescription = if (row.isCurrent) stringResource(R.string.versions_current) else null,
                tint = if (row.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        overlineContent = if (row.isCurrent) {
            { Text(stringResource(R.string.versions_current), color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        headlineContent = { Text(e.createdAt?.let { formatDateTime(it) } ?: e.id) },
        supportingContent = {
            Text(
                listOfNotNull(
                    bytesText(e.size),
                    e.storagePolicy?.name,
                    e.createdBy?.nickname?.ifEmpty { null },
                ).joinToString(" · "),
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.versions_preview)) },
                        leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                        onClick = { menu = false; onPreview() },
                    )
                    if (row.canModify) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.versions_set_current)) },
                            leadingIcon = { Icon(Icons.Filled.Restore, contentDescription = null) },
                            onClick = { menu = false; onSetCurrent() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
    )
}
