package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.SearchFileType
import plus.ling.opencloudreve.core.presentation.files.FileOperation
import plus.ling.opencloudreve.core.presentation.files.SearchFilters
import plus.ling.opencloudreve.core.presentation.files.SearchIntent
import plus.ling.opencloudreve.core.presentation.files.SearchState
import plus.ling.opencloudreve.core.presentation.files.SearchViewModel
import plus.ling.opencloudreve.ui.common.message
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * 搜索页：全屏展开的 M3 `SearchBar`。未提交时显示历史与高级过滤；提交后显示结果（名称/过滤为游标分页，
 * 全文搜索显示命中片段）。结果条目支持操作菜单（含“前往所在目录”）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    accountId: String,
    baseUri: String,
    navigator: FileNavigator,
    navController: NavHostController,
) {
    val vm: SearchViewModel = koinViewModel { parametersOf(accountId, baseUri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user = LocalAccountUser.current
    val snackbar = remember { SnackbarHostState() }
    val controller = rememberFileActionsController()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val loader = remember(vm) { ThumbnailLoader { vm.thumbnailUrl(it) } }
    val keyboard = LocalSoftwareKeyboardController.current

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = state.query,
                onQueryChange = { vm.on(SearchIntent.SetQuery(it)) },
                onSearch = {
                    showFilters = false
                    keyboard?.hide()
                    vm.on(SearchIntent.Submit)
                },
                expanded = true,
                onExpandedChange = { if (!it) navigator.back() },
                placeholder = { Text(stringResource(R.string.search_in, folderTitle(baseUri))) },
                leadingIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                trailingIcon = {
                    Row {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.on(SearchIntent.Clear) }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                            }
                        }
                        IconButton(onClick = { showFilters = !showFilters }) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = stringResource(R.string.search_filters),
                                tint = if (!state.filters.isEmpty || state.fullText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) navigator.back() },
        // 不能给 SearchBar 加 fillMaxSize：它会把最小高度传给输入框，导致输入框占满全屏、结果区高度为 0。
        // 展开态的 SearchBar 自己会铺满可用空间。
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showFilters) {
                SearchFiltersPanel(
                    state = state,
                    onFilters = { vm.on(SearchIntent.SetFilters(it)) },
                    onFullText = { vm.on(SearchIntent.SetFullText(it)) },
                    onApply = {
                        showFilters = false
                        keyboard?.hide()
                        vm.on(SearchIntent.Submit)
                    },
                    // 面板比屏幕高（尤其键盘弹出时），需可滚动，否则底部“搜索”按钮不可达
                    modifier = Modifier.weight(1f, fill = false),
                )
                HorizontalDivider()
            }
            val error = state.error
            when {
                !state.submitted -> SearchHistory(
                    history = state.history,
                    onUse = { vm.on(SearchIntent.UseHistory(it)) },
                    onDelete = { vm.on(SearchIntent.DeleteHistory(it)) },
                    onClear = { vm.on(SearchIntent.ClearHistory) },
                )
                state.loading -> SkeletonList()
                error != null && state.results.isEmpty() && state.fullTextHits.isEmpty() ->
                    FullError(error) { vm.on(SearchIntent.Submit) }
                state.results.isEmpty() && state.fullTextHits.isEmpty() ->
                    CenteredMessage(Icons.Outlined.SearchOff, stringResource(R.string.search_no_results))
                else -> SearchResults(
                    state = state,
                    loader = loader,
                    onOpen = { file ->
                        if (file.isFolder) navigator.openFolder(file.path) else navigator.openFile(file, state.searchUri ?: baseUri)
                    },
                    onMore = { controller.request(FileActionRequest.ShowSheet(listOf(it))) },
                    onLoadMore = { vm.on(SearchIntent.LoadMore) },
                )
            }
        }
        SnackbarHost(snackbar)
    }

    FileActionsHost(
        controller = controller,
        accountId = accountId,
        listingUri = state.searchUri ?: baseUri,
        user = user,
        viewerResolver = null,
        inSearch = true,
        navigator = navigator,
        navController = navController,
        snackbar = snackbar,
        onChanged = { op, _ ->
            // 名称/位置变化后重新搜索，保持结果准确
            if (op !is FileOperation.CreateSourceLinks) vm.on(SearchIntent.Submit)
        },
    )
}

@Composable
private fun SearchHistory(history: List<String>, onUse: (String) -> Unit, onDelete: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) {
        CenteredMessage(Icons.Filled.History, stringResource(R.string.search_history_empty))
        return
    }
    LazyColumn {
        item(key = "header", contentType = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_history_clear)) }
            }
        }
        items(history, key = { it }, contentType = { "history" }) { q ->
            ListItem(
                modifier = Modifier.clickable { onUse(q) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { onDelete(q) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_history_delete, q))
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    loader: ThumbnailLoader?,
    onOpen: (FileItem) -> Unit,
    onMore: (FileItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val fullText = state.fullTextHits.isNotEmpty()
    val count = if (fullText) state.fullTextHits.size else state.results.size
    LoadMoreEffect(listState, count, state.hasMore, onLoadMore)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (fullText) {
            item(key = "total", contentType = "header") {
                Text(
                    pluralStringResource(R.plurals.search_fulltext_total, state.fullTextTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state.fullTextTotal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.fullTextHits, key = { "ft:" + it.file.path }, contentType = { "hit" }) { hit ->
                FileRow(
                    file = hit.file,
                    loader = loader,
                    inTrash = false,
                    selecting = false,
                    selected = false,
                    onClick = { onOpen(hit.file) },
                    onLongClick = { onMore(hit.file) },
                    onMore = { onMore(hit.file) },
                    supporting = null,
                )
                Text(
                    highlightSnippet(hit.content, state.query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        } else {
            items(state.results, key = { it.path }, contentType = { if (it.isFolder) "folder" else "file" }) { file ->
                FileRow(
                    file = file,
                    loader = loader,
                    inTrash = false,
                    selecting = false,
                    selected = false,
                    onClick = { onOpen(file) },
                    onLongClick = { onMore(file) },
                    onMore = { onMore(file) },
                    supporting = CrUri.parseOrNull(file.path)?.parent()?.path,
                )
            }
        }
        if (state.loadingMore) item(key = "more", contentType = "more") { LoadingMore() }
        state.error?.let { err ->
            item(key = "error", contentType = "error") {
                Text(err.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** 全文命中片段：服务端可能用 `<mark>` 等标签包裹命中词，去标签后对查询词加粗 */
private fun highlightSnippet(content: String, query: String): AnnotatedString {
    val plain = content.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
    val q = query.trim()
    return buildAnnotatedString {
        append(plain)
        if (q.isNotEmpty()) {
            var from = 0
            while (true) {
                val i = plain.indexOf(q, from, ignoreCase = true)
                if (i < 0) break
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, i + q.length)
                from = i + q.length
            }
        }
    }
}

/** 时间范围预设（天数；null 为不限） */
private val TimePresets = listOf<Int?>(null, 1, 7, 30, 365)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchFiltersPanel(
    state: SearchState,
    onFilters: (SearchFilters) -> Unit,
    onFullText: (Boolean) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = state.filters
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.fullTextAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_fulltext), modifier = Modifier.weight(1f))
                Switch(checked = state.fullText, onCheckedChange = onFullText)
            }
        }
        if (!state.fullText) {
            Text(stringResource(R.string.search_type), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = f.type == null, onClick = { onFilters(f.copy(type = null)) }, label = { Text(stringResource(R.string.search_any)) })
                FilterChip(
                    selected = f.type == SearchFileType.FILE,
                    onClick = { onFilters(f.copy(type = SearchFileType.FILE)) },
                    label = { Text(stringResource(R.string.search_type_file)) },
                )
                FilterChip(
                    selected = f.type == SearchFileType.FOLDER,
                    onClick = { onFilters(f.copy(type = SearchFileType.FOLDER)) },
                    label = { Text(stringResource(R.string.search_type_folder)) },
                )
            }
            Text(stringResource(R.string.search_category), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to R.string.search_any,
                    SearchFilters.CATEGORY_IMAGE to R.string.search_category_image,
                    SearchFilters.CATEGORY_VIDEO to R.string.search_category_video,
                    SearchFilters.CATEGORY_AUDIO to R.string.search_category_audio,
                    SearchFilters.CATEGORY_DOCUMENT to R.string.search_category_document,
                ).forEach { (value, label) ->
                    FilterChip(selected = f.category == value, onClick = { onFilters(f.copy(category = value)) }, label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.search_size_mb), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MegabyteField(stringResource(R.string.search_min), f.sizeGte, Modifier.weight(1f)) { onFilters(f.copy(sizeGte = it)) }
                MegabyteField(stringResource(R.string.search_max), f.sizeLte, Modifier.weight(1f)) { onFilters(f.copy(sizeLte = it)) }
            }
            Text(stringResource(R.string.search_updated_within), style = MaterialTheme.typography.labelLarge)
            TimePresetRow(f.updatedGte) { onFilters(f.copy(updatedGte = it)) }
            Text(stringResource(R.string.search_created_within), style = MaterialTheme.typography.labelLarge)
            TimePresetRow(f.createdGte) { onFilters(f.copy(createdGte = it)) }
            var tags by rememberSaveable { mutableStateOf(f.tags.joinToString(", ")) }
            OutlinedTextField(
                value = tags,
                onValueChange = { value ->
                    tags = value
                    onFilters(f.copy(tags = value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }))
                },
                label = { Text(stringResource(R.string.search_tags)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_case_insensitive), modifier = Modifier.weight(1f))
                Switch(checked = f.caseFolding, onCheckedChange = { onFilters(f.copy(caseFolding = it)) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onFilters(SearchFilters()) }) { Text(stringResource(R.string.search_reset_filters)) }
            TextButton(onClick = onApply, enabled = state.canSubmit) { Text(stringResource(R.string.search)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimePresetRow(currentGte: Long?, onChange: (Long?) -> Unit) {
    val now = remember { Clock.System.now() }
    // 按当前值反推所选预设（允许 1 小时误差）
    val selectedDays = currentGte?.let { gte -> TimePresets.filterNotNull().minByOrNull { kotlin.math.abs(now.epochSeconds - it * 86_400L - gte) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimePresets.forEach { days ->
            FilterChip(
                selected = if (days == null) currentGte == null else selectedDays == days,
                onClick = { onChange(days?.let { (now - it.days).epochSeconds }) },
                label = {
                    Text(
                        when (days) {
                            null -> stringResource(R.string.search_any)
                            1 -> stringResource(R.string.search_within_day)
                            7 -> stringResource(R.string.search_within_week)
                            30 -> stringResource(R.string.search_within_month)
                            else -> stringResource(R.string.search_within_year)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun MegabyteField(label: String, bytes: Long?, modifier: Modifier, onChange: (Long?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(bytes?.let { (it / (1024 * 1024)).toString() }.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val digits = v.filter { it.isDigit() }.take(9)
            text = digits
            onChange(digits.toLongOrNull()?.let { it * 1024 * 1024 })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
