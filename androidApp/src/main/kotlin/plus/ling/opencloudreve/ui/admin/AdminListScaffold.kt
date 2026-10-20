package plus.ling.opencloudreve.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.OrderDirection
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.admin.AdminListEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminListState
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.openCustomTab

/**
 * 收集管理列表的通用事件：删除完成/失败 Snackbar、打开交接地址；其余事件交给 [onEffect]。
 * Snackbar 在独立协程中显示，不阻塞后续事件。
 */
@Composable
internal fun CollectAdminListEffects(
    effects: Flow<AdminListEffect>,
    snackbar: SnackbarHostState,
    onEffect: suspend (AdminListEffect) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    CollectEffects(effects) { effect ->
        when (effect) {
            is AdminListEffect.Deleted -> scope.launch {
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.admin_deleted, effect.count, effect.count))
            }
            is AdminListEffect.Failed -> scope.launch { snackbar.showSnackbar(adminErrorText(context, effect.error)) }
            is AdminListEffect.OpenUrl -> openCustomTab(context, effect.url)
            else -> onEffect(effect)
        }
    }
}

/**
 * 管理端通用列表脚手架：骨架/空态/错误重试、下拉刷新、滚动到底加载更多、长按多选与批量删除、
 * 筛选行、排序菜单、总数、在网页后台打开、详情底部面板。
 *
 * @param supportsDelete 为 false 时不进入多选（节点）
 * @param onItemClick 非多选状态下点按行
 * @param filters 筛选 chip（放在横向滚动行中；有条件时末尾自动追加“清除筛选”）
 * @param header 列表上方的固定内容（如队列指标）
 * @param menu TopAppBar 溢出菜单项（参数为关闭菜单）
 * @param detailSheet [AdminListState.detail] 非空时在底部面板中显示的详情
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun <T : Any> AdminListScaffold(
    title: String,
    state: AdminListState<T>,
    onIntent: (AdminListIntent) -> Unit,
    itemKey: (T) -> Int,
    supportsDelete: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onItemClick: (T) -> Unit,
    filters: (@Composable () -> Unit)? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    detailSheet: (@Composable ColumnScope.(T) -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    val context = LocalContext.current
    val intent by rememberUpdatedState(onIntent)
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(enabled = state.selecting) { intent(AdminListIntent.ClearSelection) }

    // 已有数据时刷新失败：Snackbar 提示后清除错误（无数据时显示整页错误）
    val error = state.error
    val hasItems = state.items.isNotEmpty()
    val scope = rememberCoroutineScope()
    LaunchedEffect(error, hasItems) {
        if (error != null && hasItems) {
            intent(AdminListIntent.DismissError)
            // DismissError 会使本 effect 重启，Snackbar 放在独立协程中显示
            scope.launch { snackbar.showSnackbar(adminErrorText(context, error)) }
        }
    }

    Scaffold(
        contentWindowInsets = adminContentInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    deleting = state.deleting,
                    onClose = { intent(AdminListIntent.ClearSelection) },
                    onSelectAll = { intent(AdminListIntent.SelectAll) },
                    onDelete = { confirmDelete = true },
                )
            } else {
                ListTopBar(title, state, snackbar, onBack, intent, menu)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { intent(AdminListIntent.Refresh) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                header?.invoke(this)
                if (filters != null) {
                    FilterRow {
                        filters()
                        if (state.conditions.isNotEmpty()) {
                            AssistChip(
                                onClick = { intent(AdminListIntent.ClearConditions) },
                                label = { Text(stringResource(R.string.admin_filter_clear)) },
                                leadingIcon = { Icon(Icons.Outlined.FilterAltOff, contentDescription = null) },
                            )
                        }
                    }
                }
                if (state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    state.loading && state.items.isEmpty() -> SkeletonList()
                    error != null && state.items.isEmpty() -> FullError(error) { intent(AdminListIntent.Refresh) }
                    state.items.isEmpty() -> EmptyView(filtered = state.conditions.isNotEmpty())
                    else -> ItemList(state, itemKey, supportsDelete, intent, onItemClick, itemContent)
                }
            }
        }
    }

    val detail = state.detail
    if (detailSheet != null && detail != null) {
        ModalBottomSheet(
            onDismissRequest = { intent(AdminListIntent.CloseDetail) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            if (state.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Box {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    detailSheet(detail)
                }
                // 底部面板是独立窗口，会遮住 Scaffold 的 Snackbar：面板内同步显示同一 SnackbarHostState
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (confirmDelete) {
        val count = state.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.admin_delete_confirm_title)) },
            text = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.admin_delete_confirm_message, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    intent(AdminListIntent.DeleteSelected)
                }) { Text(stringResource(R.string.admin_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> ListTopBar(
    title: String,
    state: AdminListState<T>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onIntent: (AdminListIntent) -> Unit,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        title = {
            Column {
                Text(title, maxLines = 1)
                if (!state.loading && state.error == null) {
                    Text(
                        stringResource(R.string.admin_total_count, state.totalItems),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (state.orderByOptions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { sortOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.admin_sort))
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        // 空 orderBy 表示按 id，与 "id" 选项等价
                        val current = state.orderBy.ifEmpty { "id" }
                        state.orderByOptions.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(orderLabel(key)) },
                                leadingIcon = { CheckSlot(current == key) },
                                onClick = {
                                    sortOpen = false
                                    onIntent(AdminListIntent.SetOrder(key, state.orderDirection))
                                },
                            )
                        }
                        HorizontalDivider()
                        listOf(OrderDirection.ASC to R.string.admin_sort_asc, OrderDirection.DESC to R.string.admin_sort_desc)
                            .forEach { (dir, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { CheckSlot(state.orderDirection == dir) },
                                    onClick = {
                                        sortOpen = false
                                        onIntent(AdminListIntent.SetOrder(state.orderBy, dir))
                                    },
                                )
                            }
                    }
                }
            }
            OpenInWebAction(
                handoff = state.handoff,
                opening = state.openingWeb,
                serverVersion = null,
                snackbar = snackbar,
                onOpen = { onIntent(AdminListIntent.OpenInWeb) },
            )
            if (menu != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.admin_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menu { menuOpen = false }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, deleting: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.admin_clear_selection))
            }
        },
        title = { Text(stringResource(R.string.admin_selected_count, count)) },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.admin_select_all))
            }
            IconButton(onClick = onDelete, enabled = !deleting && count > 0) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.admin_delete))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T : Any> ItemList(
    state: AdminListState<T>,
    itemKey: (T) -> Int,
    supportsDelete: Boolean,
    onIntent: (AdminListIntent) -> Unit,
    onItemClick: (T) -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.items.size) {
        if (nearEnd && state.hasMore) onIntent(AdminListIntent.LoadMore)
    }
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    val enterSelection = stringResource(R.string.admin_enter_selection)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.items, key = itemKey) { item ->
            val id = itemKey(item)
            val selected = id in state.selection
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) selectedColor else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            if (state.selecting) {
                                if (supportsDelete) onIntent(AdminListIntent.ToggleSelection(id))
                            } else {
                                onItemClick(item)
                            }
                        },
                        onLongClick = if (supportsDelete) {
                            { onIntent(AdminListIntent.ToggleSelection(id)) }
                        } else {
                            null
                        },
                        onLongClickLabel = if (supportsDelete) enterSelection else null,
                    ),
            ) { itemContent(item) }
        }
        if (state.loadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 5

/** 首次加载骨架 */
@Composable
private fun SkeletonList() {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        repeat(8) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.fillMaxWidth(0.5f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(color))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

@Composable
private fun EmptyView(filtered: Boolean) {
    // 可滚动以支持下拉刷新
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Inbox, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (filtered) R.string.admin_empty_filtered else R.string.admin_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 整页错误 + 重试 */
@Composable
internal fun FullError(error: UiError, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.admin_load_failed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            adminErrorText(LocalContext.current, error),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** 详情面板底部的操作按钮行 */
@Composable
internal fun SheetActions(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
