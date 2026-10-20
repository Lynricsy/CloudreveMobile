package plus.ling.opencloudreve.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.model.ShareOrderBy
import plus.ling.opencloudreve.core.presentation.share.ShareListEffect
import plus.ling.opencloudreve.core.presentation.share.ShareListIntent
import plus.ling.opencloudreve.core.presentation.share.ShareListState
import plus.ling.opencloudreve.core.presentation.share.ShareListViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.CenteredMessage
import plus.ling.opencloudreve.ui.files.FullError
import plus.ling.opencloudreve.ui.files.formatDateTime
import plus.ling.opencloudreve.ui.files.rememberCopyToClipboard
import plus.ling.opencloudreve.ui.settings.QrCode

/**
 * “分享”Tab：我的分享（游标分页），复制链接/二维码/系统分享/编辑/删除/跳转源文件；长按进入多选批量删除。
 * @param openFilesAt 在“文件”Tab 打开目录（跳转源文件所在位置）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesTab(accountId: String, openFilesAt: (String) -> Unit) {
    val vm: ShareListViewModel = koinViewModel(key = "shares:$accountId") { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var qrShare by remember { mutableStateOf<Share?>(null) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ShareListEffect.Deleted -> snackbar.showSnackbar(resources.getQuantityString(R.plurals.share_deleted, effect.count, effect.count))
            is ShareListEffect.OpenSource -> {
                val share = state.shares.firstOrNull { it.id == effect.shareId }
                val uri = CrUri.parseOrNull(effect.sourceUri)
                // 目录直接打开；文件打开其所在目录
                val target = if (share?.isFolder == true) uri else uri?.parent()
                openFilesAt((target ?: uri)?.toString() ?: effect.sourceUri)
            }
            is ShareListEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }
    BackHandler(enabled = state.selecting) { vm.on(ShareListIntent.ExitSelection) }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { vm.on(ShareListIntent.ExitSelection) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    title = { Text(stringResource(R.string.selection_count, state.selection.size)) },
                    actions = {
                        IconButton(onClick = { vm.on(ShareListIntent.SelectAll) }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.selection_all))
                        }
                        IconButton(onClick = { confirmDelete = state.selection.toList() }, enabled = state.selection.isNotEmpty() && !state.deleting) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.tab_shares)) },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.share_sort))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                state.orderByOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(option)) },
                                        leadingIcon = { RadioButton(selected = state.orderBy == option, onClick = null) },
                                        onClick = {
                                            sortMenu = false
                                            vm.on(ShareListIntent.SetSort(option, state.orderDirection))
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = state.error
            when {
                state.loading && state.shares.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && state.shares.isEmpty() -> FullError(error) { vm.on(ShareListIntent.Refresh) }
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.on(ShareListIntent.Refresh) }) {
                    if (state.isEmpty) {
                        CenteredMessage(Icons.Outlined.Share, stringResource(R.string.placeholder_shares))
                    } else {
                        ShareList(
                            state = state,
                            onIntent = vm::on,
                            onCopy = { share -> share.url },
                            onQr = { qrShare = it },
                            onEdit = { editing = it.id },
                            onDelete = { confirmDelete = listOf(it.id) },
                            snackbar = snackbar,
                        )
                    }
                }
            }
        }
    }

    editing?.let { id ->
        ShareEditDialog(
            accountId = accountId,
            shareId = id,
            onDismiss = { editing = null },
            onSaved = { vm.on(ShareListIntent.Refresh) },
        )
    }
    qrShare?.let { share -> ShareQrDialog(share, onDismiss = { qrShare = null }) }
    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(pluralStringResource(R.plurals.share_delete_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.share_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.on(ShareListIntent.Delete(ids))
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun sortLabel(option: String?): String = stringResource(
    when (option) {
        ShareOrderBy.VIEWS -> R.string.share_sort_views
        ShareOrderBy.DOWNLOADS -> R.string.share_sort_downloads
        ShareOrderBy.REMAIN_DOWNLOADS -> R.string.share_sort_remain
        else -> R.string.share_sort_created
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareList(
    state: ShareListState,
    onIntent: (ShareListIntent) -> Unit,
    onCopy: (Share) -> String,
    onQr: (Share) -> Unit,
    onEdit: (Share) -> Unit,
    onDelete: (Share) -> Unit,
    snackbar: SnackbarHostState,
) {
    val listState = rememberLazyListState()
    val copy = rememberCopyToClipboard(snackbar)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.shares.size) {
        if (nearEnd && state.hasMore && !state.loadingMore) onIntent(ShareListIntent.LoadMore)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.shares, key = Share::id) { share ->
            val selected = share.id in state.selection
            var menu by remember { mutableStateOf(false) }
            ListItem(
                modifier = Modifier.combinedClickable(
                    onClick = { if (state.selecting) onIntent(ShareListIntent.ToggleSelection(share.id)) else menu = true },
                    onLongClick = { onIntent(ShareListIntent.ToggleSelection(share.id)) },
                ),
                colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                leadingContent = {
                    Icon(
                        when {
                            selected -> Icons.Filled.CheckCircle
                            share.isFolder -> Icons.Outlined.Folder
                            else -> Icons.Outlined.Description
                        },
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(share.name ?: share.id, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                },
                supportingContent = { Text(shareSubtitle(share), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = if (state.selecting) {
                    null
                } else {
                    {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_more_actions))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem(Icons.Filled.ContentCopy, R.string.action_copy_link) {
                                    menu = false
                                    scope.launch { copy(onCopy(share)) }
                                }
                                MenuItem(Icons.Filled.QrCode2, R.string.share_qr) {
                                    menu = false
                                    onQr(share)
                                }
                                MenuItem(Icons.Filled.Share, R.string.share_system_share) {
                                    menu = false
                                    shareLinkViaSystem(context, share.name ?: share.id, share.url)
                                }
                                MenuItem(Icons.Filled.Edit, R.string.share_edit) {
                                    menu = false
                                    onEdit(share)
                                }
                                MenuItem(Icons.Filled.FolderOpen, R.string.share_open_source) {
                                    menu = false
                                    onIntent(ShareListIntent.OpenSource(share.id))
                                }
                                MenuItem(Icons.Filled.Delete, R.string.action_delete) {
                                    menu = false
                                    onDelete(share)
                                }
                            }
                        }
                    }
                },
            )
        }
        if (state.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/** 访问/下载计数、剩余下载、过期、密码 */
@Composable
private fun shareSubtitle(share: Share): String {
    val parts = buildList {
        add(pluralStringResource(R.plurals.info_views, share.visited, share.visited))
        if (!share.isFolder) add(pluralStringResource(R.plurals.share_downloads, share.downloaded, share.downloaded))
        share.remainDownloads?.takeIf { it > 0 }?.let { add(pluralStringResource(R.plurals.share_remain_downloads, it, it)) }
        when {
            share.expired -> add(stringResource(R.string.info_share_expired))
            share.expires != null -> add(stringResource(R.string.info_share_expires, formatDateTime(share.expires!!)))
        }
        share.password?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.share_password_value, it)) }
    }
    return parts.joinToString(" · ")
}

/** 分享二维码 */
@Composable
private fun ShareQrDialog(share: Share, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (share.password.isNullOrEmpty()) null else { { Icon(Icons.Filled.Lock, contentDescription = null) } },
        title = { Text(share.name ?: share.id, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QrCode(share.url, contentDescription = stringResource(R.string.share_qr_cd), size = 220.dp)
                Text(share.url, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
