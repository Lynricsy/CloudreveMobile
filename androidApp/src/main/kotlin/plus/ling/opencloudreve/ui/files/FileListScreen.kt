package plus.ling.opencloudreve.ui.files

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.files.NewFileOption
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.ExplorerView
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileOrderBy
import plus.ling.opencloudreve.core.model.OrderDirection
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.files.FileListEffect
import plus.ling.opencloudreve.core.presentation.files.FileListIntent
import plus.ling.opencloudreve.core.presentation.files.FileListState
import plus.ling.opencloudreve.core.presentation.files.FileListViewModel
import plus.ling.opencloudreve.core.presentation.files.FileOperation
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/** 距离末尾多少项时预加载下一页 */
private const val PREFETCH_DISTANCE = 12

/**
 * 文件列表页：目录、回收站、与我共享、分享内目录、搜索 URI。
 * 列表/网格、排序、缩略图开关、多选、下拉刷新、滚动到底加载更多；操作交给 [FileActionsHost]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    accountId: String,
    uri: String,
    navigator: FileNavigator,
    navController: NavHostController,
    canGoBack: Boolean,
    pickResult: FolderPickResult?,
    onPickResultConsumed: () -> Unit,
) {
    val vm: FileListViewModel = koinViewModel { parametersOf(accountId, uri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val controller = rememberFileActionsController()
    val context = LocalContext.current
    val features = LocalFileFeatures.current
    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    val loader = remember(vm) { ThumbnailLoader { vm.thumbnailUrl(it) } }
    val consumed by rememberUpdatedState(onPickResultConsumed)

    LifecycleStartEffect(vm) {
        vm.on(FileListIntent.SetForeground(true))
        onStopOrDispose { vm.on(FileListIntent.SetForeground(false)) }
    }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is FileListEffect.ShowError -> snackbar.showSnackbar(effect.error.message(context))
        }
    }
    LaunchedEffect(pickResult) {
        if (pickResult != null) {
            controller.request(FileActionRequest.PickResult(pickResult))
            consumed()
        }
    }
    BackHandler(enabled = state.selecting) { vm.on(FileListIntent.ExitSelection) }

    val onOpen: (FileItem) -> Unit = { file ->
        when {
            state.selecting -> vm.on(FileListIntent.ToggleSelection(file.path))
            file.isFolder -> navigator.openFolder(if (file.isSymbolic) file.linkedUri else vm.childUri(file))
            else -> navigator.openFile(file, uri)
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val title = if (state.isRoot && !state.isSearch) folderTitle(uri) else state.title.ifEmpty { folderTitle(uri) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    onClose = { vm.on(FileListIntent.ExitSelection) },
                    onSelectAll = { vm.on(FileListIntent.SelectAll) },
                )
            } else {
                FolderTopBar(
                    title = title,
                    state = state,
                    canGoBack = canGoBack,
                    onBack = navigator::back,
                    onSearch = { navigator.openSearch(CrUri.parse(uri).withoutSearch().toString()) },
                    onIntent = vm::on,
                    onSelect = { vm.on(FileListIntent.EnterSelection) },
                    onEmptyTrash = { controller.request(FileActionRequest.EmptyTrash) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                SelectionBottomBar(
                    state = state,
                    features = features,
                    onAction = { action -> controller.request(FileActionRequest.Perform(action, state.selectedFiles)) },
                    onMore = { controller.request(FileActionRequest.ShowSheet(state.selectedFiles)) },
                )
            }
        },
        floatingActionButton = {
            if (state.canCreate && !state.selecting && !state.isSearch) {
                FloatingActionButton(onClick = { showNewSheet = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_menu))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.on(FileListIntent.Refresh) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.fromCache && !state.loading) {
                    Text(
                        stringResource(R.string.files_offline_cache),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val error = state.error
                when {
                    state.loading && state.files.isEmpty() -> SkeletonList()
                    error != null && state.files.isEmpty() -> FullError(error) { vm.on(FileListIntent.Refresh) }
                    state.files.isEmpty() -> EmptyFolder(state)
                    else -> FileCollection(
                        state = state,
                        loader = if (state.showThumbnails) loader else null,
                        onOpen = onOpen,
                        onLongPress = { vm.on(FileListIntent.ToggleSelection(it.path)) },
                        onMore = { controller.request(FileActionRequest.ShowSheet(listOf(it))) },
                        onLoadMore = { vm.on(FileListIntent.LoadMore) },
                    )
                }
            }
        }
    }

    FileActionsHost(
        controller = controller,
        accountId = accountId,
        listingUri = uri,
        user = state.user,
        viewerResolver = state.viewerResolver,
        inSearch = state.isSearch,
        navigator = navigator,
        navController = navController,
        snackbar = snackbar,
        onChanged = { op, _ ->
            if (state.selecting) vm.on(FileListIntent.ExitSelection)
            when (op) {
                is FileOperation.Move -> vm.on(FileListIntent.RemoveLocally(op.uris))
                is FileOperation.Delete -> vm.on(FileListIntent.RemoveLocally(op.uris))
                is FileOperation.Restore -> vm.on(FileListIntent.RemoveLocally(op.uris))
                else -> vm.on(FileListIntent.Refresh)
            }
        },
    )

    if (showNewSheet) {
        NewItemSheet(
            options = remember(state.viewerResolver) { state.viewerResolver?.newFileOptions().orEmpty() },
            canUpload = state.canUpload,
            features = features,
            onDismiss = { showNewSheet = false },
            onNewFolder = { controller.request(FileActionRequest.NewFolder(uri)) },
            onNewFile = { controller.request(FileActionRequest.NewFile(uri, it)) },
            onUploadFiles = { features.uploadFiles?.run(FileHookContext(accountId, uri, navController, navigator), uri) },
            onUploadMedia = { features.uploadMedia?.run(FileHookContext(accountId, uri, navController, navigator), uri) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTopBar(
    title: String,
    state: FileListState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onIntent: (FileListIntent) -> Unit,
    onSelect: () -> Unit,
    onEmptyTrash: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    TopAppBar(
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (!state.loading || state.files.isNotEmpty()) {
                    val count = state.files.size
                    Text(
                        if (state.hasMore) {
                            pluralStringResource(R.plurals.files_count_more, count, count)
                        } else {
                            pluralStringResource(R.plurals.files_count, count, count)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (!state.isSearch && state.fs != CrUri.FS_SHARED_WITH_ME) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
            }
            val isList = state.viewMode == ExplorerView.VIEW_LIST
            IconButton(onClick = {
                onIntent(FileListIntent.SetViewMode(if (isList) ExplorerView.VIEW_GRID else ExplorerView.VIEW_LIST))
            }) {
                Icon(
                    if (isList) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = stringResource(if (isList) R.string.view_grid else R.string.view_list),
                )
            }
            if (state.canSort) {
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        state.orderByOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(orderByLabel(option))) },
                                leadingIcon = { CheckMark(option == state.orderBy) },
                                onClick = {
                                    sortMenu = false
                                    onIntent(FileListIntent.SetSort(option, state.orderDirection))
                                },
                            )
                        }
                        if (state.orderDirectionOptions.isNotEmpty()) HorizontalDivider()
                        state.orderDirectionOptions.forEach { dir ->
                            DropdownMenuItem(
                                text = { Text(stringResource(if (dir == OrderDirection.DESC) R.string.sort_desc else R.string.sort_asc)) },
                                leadingIcon = { CheckMark(dir == state.orderDirection) },
                                onClick = {
                                    sortMenu = false
                                    onIntent(FileListIntent.SetSort(state.orderBy, dir))
                                },
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_thumbnails)) },
                        leadingIcon = { CheckMark(state.showThumbnails) },
                        onClick = {
                            overflow = false
                            onIntent(FileListIntent.SetThumbnails(!state.showThumbnails))
                        },
                    )
                    if (state.files.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.selection_enter)) },
                            leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                            onClick = {
                                overflow = false
                                onSelect()
                            },
                        )
                    }
                    if (state.isTrash && state.isRoot && !state.isSearch && state.files.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                overflow = false
                                onEmptyTrash()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun CheckMark(checked: Boolean) {
    if (checked) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp))
}

private fun orderByLabel(option: String): Int = when (option) {
    FileOrderBy.NAME -> R.string.sort_name
    FileOrderBy.SIZE -> R.string.sort_size
    FileOrderBy.UPDATED_AT -> R.string.sort_updated
    else -> R.string.sort_created
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_exit))
            }
        },
        title = { Text(stringResource(R.string.selection_count, count)) },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.selection_all))
            }
        },
    )
}

/** 多选底栏：下载（已接入时）、移动、复制、删除（回收站为还原、删除），其余放进“更多” */
@Composable
private fun SelectionBottomBar(
    state: FileListState,
    features: FileFeatures,
    onAction: (FileAction) -> Unit,
    onMore: () -> Unit,
) {
    val selected = state.selectedFiles
    val overLimit = state.maxBatchSize > 0 && selected.size > state.maxBatchSize
    val available = remember(selected, state.user, state.viewerResolver, features) {
        visibleActions(selected, state.user, state.viewerResolver, state.isSearch, features)
    }
    val primary = listOf(FileAction.Download, FileAction.Restore, FileAction.Move, FileAction.Copy, FileAction.Delete)
        .filter { it in available }
    BottomAppBar {
        if (overLimit) {
            Text(
                pluralStringResource(R.plurals.selection_over_limit, state.maxBatchSize, state.maxBatchSize),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp).weight(1f),
            )
        } else {
            primary.forEach { action ->
                BottomAction(action.icon(false), action.labelText(), enabled = selected.isNotEmpty()) { onAction(action) }
            }
            Box(Modifier.weight(1f))
            BottomAction(Icons.Filled.MoreHoriz, stringResource(R.string.more_options), enabled = selected.isNotEmpty(), onClick = onMore)
        }
    }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}

/** 列表或网格（按视图模式）+ 滚动到底加载更多 */
@Composable
private fun FileCollection(
    state: FileListState,
    loader: ThumbnailLoader?,
    onOpen: (FileItem) -> Unit,
    onLongPress: (FileItem) -> Unit,
    onMore: (FileItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    val inTrash = state.isTrash
    if (state.viewMode == ExplorerView.VIEW_LIST) {
        val listState = rememberLazyListState()
        LoadMoreEffect(listState, state.files.size, state.hasMore) { loadMore() }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(state.files, key = { it.path }, contentType = { if (it.isFolder) "folder" else "file" }) { file ->
                FileRow(
                    file = file,
                    loader = loader,
                    inTrash = inTrash,
                    selecting = state.selecting,
                    selected = file.path in state.selection,
                    onClick = { onOpen(file) },
                    onLongClick = { onLongPress(file) },
                    onMore = { onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.loadingMore) item(key = "more", contentType = "more") { LoadingMore() }
        }
    } else {
        val gridState = rememberLazyGridState()
        LoadMoreEffect(gridState, state.files.size, state.hasMore) { loadMore() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.files.size, key = { state.files[it].path }, contentType = { if (state.files[it].isFolder) "folder" else "file" }) { i ->
                val file = state.files[i]
                FileGridCell(
                    file = file,
                    loader = loader,
                    inTrash = inTrash,
                    selecting = state.selecting,
                    selected = file.path in state.selection,
                    onClick = { onOpen(file) },
                    onLongClick = { onLongPress(file) },
                    onMore = { onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.loadingMore) {
                item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "more") { LoadingMore() }
            }
        }
    }
}

@Composable
internal fun LoadMoreEffect(state: LazyListState, count: Int, hasMore: Boolean, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, count, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= count - PREFETCH_DISTANCE }
            .collect { load() }
    }
}

@Composable
private fun LoadMoreEffect(state: LazyGridState, count: Int, hasMore: Boolean, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, count, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= count - PREFETCH_DISTANCE }
            .collect { load() }
    }
}

@Composable
internal fun LoadingMore() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
internal fun SkeletonList() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = false)) {
        repeat(10) { FileRowSkeleton() }
    }
}

@Composable
internal fun FullError(error: UiError, onRetry: () -> Unit) {
    CenteredMessage(
        icon = Icons.Outlined.FolderOff,
        text = error.message(),
        action = { Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } },
    )
}

@Composable
private fun EmptyFolder(state: FileListState) {
    CenteredMessage(
        icon = Icons.Outlined.FolderOff,
        text = stringResource(
            when {
                state.isSearch -> R.string.search_no_results
                state.isTrash -> R.string.trash_empty_state
                state.fs == CrUri.FS_SHARED_WITH_ME -> R.string.shared_with_me_empty
                else -> R.string.folder_empty
            },
        ),
    )
}

@Composable
internal fun CenteredMessage(icon: ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    // 可滚动，使空态/错误态也能下拉刷新
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(1.dp, 96.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** 新建菜单：新建文件夹、按预览器模板新建文件、上传（P5 接入后显示） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewItemSheet(
    options: List<NewFileOption>,
    canUpload: Boolean,
    features: FileFeatures,
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: (NewFileOption) -> Unit,
    onUploadFiles: () -> Unit,
    onUploadMedia: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.new_menu),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetItem(Icons.Filled.CreateNewFolder, stringResource(R.string.new_folder)) {
                onDismiss()
                onNewFolder()
            }
            options.forEach { option ->
                val name = templateLabel(option.template.displayName, option.template.ext)
                SheetItem(Icons.AutoMirrored.Filled.NoteAdd, stringResource(R.string.new_file_title, name)) {
                    onDismiss()
                    onNewFile(option)
                }
            }
            if (canUpload && features.uploadFiles != null) {
                SheetItem(Icons.Filled.Upload, stringResource(R.string.upload_files)) {
                    onDismiss()
                    onUploadFiles()
                }
            }
            if (canUpload && features.uploadMedia != null) {
                SheetItem(Icons.Filled.PhotoLibrary, stringResource(R.string.upload_media)) {
                    onDismiss()
                    onUploadMedia()
                }
            }
        }
    }
}

@Composable
internal fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
