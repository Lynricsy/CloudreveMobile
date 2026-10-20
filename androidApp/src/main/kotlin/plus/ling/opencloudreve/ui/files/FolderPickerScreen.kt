package plus.ling.opencloudreve.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.files.FolderPickerIntent
import plus.ling.opencloudreve.core.presentation.files.FolderPickerState
import plus.ling.opencloudreve.core.presentation.files.FolderPickerViewModel
import plus.ling.opencloudreve.ui.common.message

/**
 * 目录选择器（移动/复制/保存分享的目标；P5 上传目标、WebDAV 根目录也复用）。
 * @param confirmLabel 确认按钮文字（“移动到此处”等）
 */
@Composable
fun FolderPickerScreen(
    accountId: String,
    initialUri: String,
    excluded: List<String>,
    confirmLabel: String,
    onPicked: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val vm: FolderPickerViewModel = koinViewModel { parametersOf(accountId, initialUri, excluded) }
    val state by vm.state.collectAsStateWithLifecycle()
    FolderPickerContent(state, confirmLabel, vm::on, onPicked, onCancel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerContent(
    state: FolderPickerState,
    confirmLabel: String,
    onIntent: (FolderPickerIntent) -> Unit,
    onPicked: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(enabled = state.canGoUp) { onIntent(FolderPickerIntent.Up) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                },
                title = { Text(stringResource(R.string.picker_title)) },
                actions = {
                    if (state.canCreateFolder && !state.excluded) {
                        IconButton(onClick = { creating = true }, enabled = !state.creating) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.new_folder))
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Text(
                    when {
                        state.excluded -> stringResource(R.string.picker_excluded)
                        !state.canCreateFolder && !state.loading -> stringResource(R.string.picker_not_writable)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Button(onClick = { onPicked(state.uri) }, enabled = state.canSelect, modifier = Modifier.padding(end = 16.dp)) {
                    Text(confirmLabel)
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 面包屑
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    val last = index == state.crumbs.lastIndex
                    TextButton(onClick = { onIntent(FolderPickerIntent.Open(crumb.uri)) }, enabled = !last) {
                        Text(
                            crumb.name.ifEmpty { CrUri.parseOrNull(crumb.uri)?.let { folderTitle(context, it) } ?: crumb.uri },
                            fontWeight = if (last) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val error = state.error
            when {
                state.loading && state.folders.isEmpty() -> SkeletonList()
                error != null && state.folders.isEmpty() -> FullError(error) { onIntent(FolderPickerIntent.Refresh) }
                state.folders.isEmpty() -> CenteredMessage(Icons.Outlined.FolderOff, stringResource(R.string.picker_no_subfolders))
                else -> {
                    val listState = rememberLazyListState()
                    LoadMoreEffect(listState, state.folders.size, state.hasMore) { onIntent(FolderPickerIntent.LoadMore) }
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(state.folders, key = { it.path }) { folder ->
                            ListItem(
                                modifier = Modifier.clickable { onIntent(FolderPickerIntent.Open(folder.path)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { FileTypeIconView(folder, 32.dp) },
                                headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            )
                        }
                        if (state.loadingMore) item(key = "more") { LoadingMore() }
                    }
                }
            }
            if (error != null && state.folders.isNotEmpty()) {
                Text(
                    error.message(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).clickable { onIntent(FolderPickerIntent.DismissError) },
                )
            }
        }
    }
    if (creating) {
        NameInputDialog(
            title = stringResource(R.string.new_folder),
            initial = stringResource(R.string.new_folder_default_name),
            selectBaseName = false,
            confirm = stringResource(R.string.action_create),
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                onIntent(FolderPickerIntent.CreateFolder(it))
            },
        )
    }
}
