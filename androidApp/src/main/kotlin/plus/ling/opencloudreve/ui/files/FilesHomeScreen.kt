package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.Capacity
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.PinedFile
import plus.ling.opencloudreve.core.presentation.files.FileListIntent
import plus.ling.opencloudreve.core.presentation.files.FileListViewModel
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.files.FileOperation

/**
 * “文件”Tab 首页：容量、位置（我的文件、与我共享、回收站）、固定项（`user.pined`）。
 * 借用“我的文件”根目录的列表 VM 获取用户资料与容量（同时预热根目录缓存）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesHomeScreen(
    accountId: String,
    navigator: FileNavigator,
    navController: NavHostController,
) {
    val rootUri = remember { CrUri.my().toString() }
    val vm: FileListViewModel = koinViewModel { parametersOf(accountId, rootUri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val controller = rememberFileActionsController()
    var openLink by rememberSaveable { mutableStateOf(false) }
    var pinMenu by remember { mutableStateOf<PinedFile?>(null) }

    // 从子页面返回时刷新容量与固定项
    LifecycleResumeEffect(vm) {
        vm.on(FileListIntent.Refresh)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_files)) },
                actions = {
                    IconButton(onClick = { navigator.openSearch(rootUri) }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { openLink = true }) {
                        Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.share_open_link))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.on(FileListIntent.Refresh) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.capacity?.let { capacity ->
                    item(key = "quota", contentType = "quota") { QuotaCard(capacity) }
                }
                item(key = "locations", contentType = "header") { SectionLabel(stringResource(R.string.files_locations)) }
                item(key = "my", contentType = "location") {
                    LocationRow(Icons.Filled.Folder, stringResource(R.string.files_my)) { navigator.openFolder(rootUri) }
                }
                item(key = "shared_with_me", contentType = "location") {
                    LocationRow(Icons.Filled.FolderShared, stringResource(R.string.files_shared_with_me)) {
                        navigator.openFolder(CrUri.sharedWithMe().toString())
                    }
                }
                item(key = "trash", contentType = "location") {
                    LocationRow(Icons.Filled.Delete, stringResource(R.string.files_trash)) { navigator.openFolder(CrUri.trash().toString()) }
                }
                val pins = state.user?.pined.orEmpty()
                if (pins.isNotEmpty()) {
                    item(key = "pins", contentType = "header") { SectionLabel(stringResource(R.string.files_pinned)) }
                    items(pins, key = { "pin:" + it.uri }, contentType = { "pin" }) { pin ->
                        val context = LocalContext.current
                        val name = pin.name?.takeIf { it.isNotBlank() }
                            ?: CrUri.parseOrNull(pin.uri)?.let { folderTitle(context, it) }
                            ?: pin.uri
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { navigator.openFolder(pin.uri) },
                                onLongClick = { if (!pin.locked) pinMenu = pin },
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = if (!pin.locked) {
                                {
                                    IconButton(onClick = { pinMenu = pin }) {
                                        Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.action_unpin))
                                    }
                                    DropdownMenu(expanded = pinMenu == pin, onDismissRequest = { pinMenu = null }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_unpin)) },
                                            onClick = {
                                                pinMenu = null
                                                controller.request(FileActionRequest.Perform(FileAction.Pin, listOf(pinTarget(pin))))
                                            },
                                        )
                                    }
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

    FileActionsHost(
        controller = controller,
        accountId = accountId,
        listingUri = rootUri,
        user = state.user,
        viewerResolver = state.viewerResolver,
        inSearch = false,
        navigator = navigator,
        navController = navController,
        snackbar = snackbar,
        onChanged = { op, _ -> if (op !is FileOperation.Unpin) vm.on(FileListIntent.Refresh) },
    )

    if (openLink) {
        OpenShareLinkDialog(
            onDismiss = { openLink = false },
            onOpen = {
                openLink = false
                navigator.openShareLink(it)
            },
        )
    }
}

/** 固定项对应的操作目标（只需 path，用于取消固定） */
private fun pinTarget(pin: PinedFile) = FileItem(path = pin.uri, name = pin.name.orEmpty())

@Composable
private fun LocationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
    )
}

/** 容量卡：已用/总量与进度；超额时用错误色 */
@Composable
private fun QuotaCard(capacity: Capacity) {
    val context = LocalContext.current
    val over = capacity.total > 0 && capacity.used > capacity.total
    val progress = if (capacity.total > 0) (capacity.used.toFloat() / capacity.total).coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.quota_title), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.quota_usage, formatBytes(context, capacity.used), formatBytes(context, capacity.total)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (over) {
                Text(stringResource(R.string.quota_over), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 输入分享链接（`https://…/s/<id>[/<pw>]`、`cloudreve://…@share` 或分享 id） */
@Composable
internal fun OpenShareLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (text.isNotBlank()) onOpen(text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_open_link)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.share_link_label)) },
                placeholder = { Text("https://example.com/s/abcd") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
