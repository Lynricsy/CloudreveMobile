package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.presentation.files.FileInfoEffect
import plus.ling.opencloudreve.core.presentation.files.FileInfoIntent
import plus.ling.opencloudreve.core.presentation.files.FileInfoState
import plus.ling.opencloudreve.core.presentation.files.FileInfoViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/**
 * 文件信息（详情窗格）：基本信息、目录统计（未完成时 VM 每 5 秒刷新）、存储策略、哈希、直链、分享、标签。
 * @param uri 链接目标 URI（软链接文件传 `linkedUri`）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInfoScreen(
    accountId: String,
    uri: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: FileInfoViewModel = koinViewModel(key = "info:$uri") { parametersOf(accountId, uri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is FileInfoEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
            FileInfoEffect.Updated -> snackbar.showSnackbar(resources.getString(R.string.info_updated))
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(state.file?.name ?: stringResource(R.string.action_info), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                actions = {
                    IconButton(onClick = { vm.on(FileInfoIntent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val file = state.file
            val error = state.error
            when {
                state.loading && file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && file == null -> FullError(error) { vm.on(FileInfoIntent.Refresh) }
                file != null -> FileInfoContent(state, file, snackbar, vm::on)
            }
            if (state.mutating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileInfoContent(
    state: FileInfoState,
    file: FileItem,
    snackbar: SnackbarHostState,
    onIntent: (FileInfoIntent) -> Unit,
) {
    val context = LocalContext.current
    val copy = rememberCopyToClipboard(snackbar)
    val scope = rememberCoroutineScope()
    val ext = file.extendedInfo
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FileTypeIconView(file, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    stringResource(if (file.isFolder) R.string.info_type_folder else R.string.info_type_file),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        SectionLabel(stringResource(R.string.info_basic))
        if (!file.isFolder) InfoLine(stringResource(R.string.info_size), formatBytes(context, file.size))
        CrUri.parseOrNull(file.path)?.parent()?.let { parent ->
            InfoLine(stringResource(R.string.info_location), if (parent.isRoot()) folderTitle(context, parent) else folderTitle(context, parent.root()) + parent.path)
        }
        file.createdAt?.let { InfoLine(stringResource(R.string.info_created), formatDateTime(it)) }
        file.updatedAt?.let { InfoLine(stringResource(R.string.info_updated_at), formatDateTime(it)) }
        file.metadata[MetadataKeys.SHARED_REDIRECT]?.let { InfoLine(stringResource(R.string.info_link_target), it) }
        file.metadata[MetadataKeys.RESTORE_URI]?.let { InfoLine(stringResource(R.string.info_restore_uri), it) }

        if (file.isFolder) {
            SectionLabel(stringResource(R.string.info_folder_summary))
            val summary = file.folderSummary
            if (state.summaryPending || summary == null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.info_summary_pending), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (summary != null) {
                InfoLine(stringResource(R.string.info_size), formatBytes(context, summary.size))
                InfoLine(
                    stringResource(R.string.info_contents),
                    pluralStringResource(R.plurals.info_files, summary.files, summary.files) + " · " +
                        pluralStringResource(R.plurals.info_folders, summary.folders, summary.folders),
                )
                summary.calculatedAt?.let { InfoLine(stringResource(R.string.info_calculated_at), formatDateTime(it)) }
            }
        }

        if (ext != null) {
            SectionLabel(stringResource(R.string.info_storage))
            ext.storagePolicy?.let { policy ->
                InfoLine(
                    stringResource(R.string.info_storage_policy),
                    if (ext.storagePolicyInherited) stringResource(R.string.info_policy_inherited, policy.name) else policy.name,
                )
            }
            if (ext.storageUsed > 0) InfoLine(stringResource(R.string.info_storage_used), formatBytes(context, ext.storageUsed))
        }

        if (!file.isFolder) {
            SectionLabel(stringResource(R.string.info_hash))
            val hash = state.hash
            when {
                hash == null -> TextButton(onClick = { onIntent(FileInfoIntent.LoadHash()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.info_hash_query))
                }
                hash.sha256 != null -> InfoLine("SHA-256", hash.sha256.orEmpty(), trailing = {
                    IconButton(onClick = { scope.launch { copy(hash.sha256.orEmpty()) } }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                })
                else -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(hashStatusText(hash.status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (hash.status == "missing") {
                        TextButton(onClick = { onIntent(FileInfoIntent.LoadHash(compute = true)) }) { Text(stringResource(R.string.info_hash_compute)) }
                    } else if (hash.status == "pending") {
                        TextButton(onClick = { onIntent(FileInfoIntent.LoadHash()) }) { Text(stringResource(R.string.action_refresh)) }
                    }
                }
            }
        }

        val links = ext?.directLinks.orEmpty()
        if (links.isNotEmpty()) {
            SectionLabel(stringResource(R.string.info_direct_links))
            links.forEach { link ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(link.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.info_downloads, link.downloaded, link.downloaded),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { scope.launch { copy(link.url) } }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy_link))
                    }
                    IconButton(onClick = { onIntent(FileInfoIntent.DeleteSourceLink(link.id)) }, enabled = !state.mutating) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.info_delete_link))
                    }
                }
            }
        }

        val shares = ext?.shares.orEmpty()
        if (shares.isNotEmpty()) {
            SectionLabel(stringResource(R.string.info_shares))
            shares.forEach { share ->
                InfoLine(
                    share.name ?: share.id,
                    listOfNotNull(
                        pluralStringResource(R.plurals.info_views, share.visited, share.visited),
                        if (share.expired) stringResource(R.string.info_share_expired) else share.expires?.let { stringResource(R.string.info_share_expires, formatDateTime(it)) },
                    ).joinToString(" · "),
                    trailing = if (share.url.isNotEmpty()) {
                        {
                            IconButton(onClick = { scope.launch { copy(share.url) } }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy_link))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        val tags = state.tags
        if (tags.isNotEmpty()) {
            SectionLabel(stringResource(R.string.info_tags))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { (name, color) ->
                    AssistChip(
                        onClick = {},
                        label = { Text(name) },
                        leadingIcon = parseHexColor(color)?.let { c -> { Box(Modifier.size(10.dp).background(c, CircleShape)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun hashStatusText(status: String): String = stringResource(
    when (status) {
        "pending" -> R.string.hash_pending
        "missing" -> R.string.hash_missing
        "failed" -> R.string.hash_failed
        "uploading" -> R.string.hash_uploading
        "unsupported" -> R.string.hash_unsupported
        else -> R.string.hash_unavailable
    },
)

/** 平板详情窗格未选择文件时的占位 */
@Composable
fun FileInfoPlaceholder() {
    CenteredMessage(Icons.Outlined.Info, stringResource(R.string.info_placeholder))
}
