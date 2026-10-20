package plus.ling.opencloudreve.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.Share
import plus.ling.opencloudreve.core.presentation.share.ShareVisitEffect
import plus.ling.opencloudreve.core.presentation.share.ShareVisitIntent
import plus.ling.opencloudreve.core.presentation.share.ShareVisitState
import plus.ling.opencloudreve.core.presentation.share.ShareVisitViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/**
 * 分享浏览：分享头部（所有者、访问数、过期）→ 需要密码时输入 → 浏览目录（面包屑返回）；
 * 单文件分享直接列出该文件；“保存到我的文件”在选定目录创建分享快捷方式；README 以 Markdown 弹层显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareVisitScreen(
    accountId: String,
    link: String,
    navigator: FileNavigator,
    pickResult: FolderPickResult?,
    onPickResultConsumed: () -> Unit,
) {
    val vm: ShareVisitViewModel = koinViewModel { parametersOf(accountId, link) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var showReadme by rememberSaveable { mutableStateOf(false) }
    val consumed by rememberUpdatedState(onPickResultConsumed)

    BackHandler(enabled = state.canGoUp) { vm.on(ShareVisitIntent.Up) }
    LaunchedEffect(pickResult) {
        if (pickResult != null) {
            if (pickResult.purpose == FolderPickPurpose.SaveShare) vm.on(ShareVisitIntent.SaveToMyFiles(pickResult.destination))
            consumed()
        }
    }
    // 目录含 README 时先用 Snackbar 提示（与网页端在移动设备上的行为一致）
    val readmeName = state.readme?.name
    LaunchedEffect(readmeName) {
        if (readmeName != null) {
            val r = snackbar.showSnackbar(
                resources.getString(R.string.share_readme_found, readmeName),
                actionLabel = resources.getString(R.string.action_view),
                withDismissAction = true,
            )
            if (r == SnackbarResult.ActionPerformed) showReadme = true
        }
    }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ShareVisitEffect.Saved -> {
                val r = snackbar.showSnackbar(
                    resources.getString(R.string.share_saved, effect.shortcut.name),
                    actionLabel = resources.getString(R.string.action_view),
                )
                if (r == SnackbarResult.ActionPerformed) navigator.openFolder(effect.dstUri)
            }
            is ShareVisitEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (state.canGoUp) vm.on(ShareVisitIntent.Up) else navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    val title = state.crumbs.lastOrNull()?.name?.takeIf { !state.isRoot && it.isNotEmpty() }
                        ?: state.share?.name
                        ?: stringResource(R.string.files_share_root)
                    Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                },
                actions = {
                    if (state.readme != null) {
                        IconButton(onClick = { showReadme = true }) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = stringResource(R.string.share_readme))
                        }
                    }
                    if (state.canSave) {
                        IconButton(onClick = {
                            val target = state.shortcutTarget ?: return@IconButton
                            navigator.pickFolder(
                                FolderPickRequest(FolderPickPurpose.SaveShare, listOf(target), CrUri.my().toString()),
                            )
                        }) {
                            Icon(Icons.Filled.BookmarkAdd, contentDescription = stringResource(R.string.share_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.saving || state.loadingList && state.files.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error = state.error
            when {
                state.loading && state.share == null -> SkeletonList()
                error != null -> CenteredMessage(Icons.Outlined.LinkOff, error.message())
                state.needsPassword -> PasswordForm(state) { vm.on(ShareVisitIntent.SubmitPassword(it)) }
                else -> ShareListing(state, navigator, onOpen = { vm.on(ShareVisitIntent.Open(it)) }, onLoadMore = { vm.on(ShareVisitIntent.LoadMore) })
            }
        }
    }

    if (showReadme) {
        ReadmeSheet(state, onDismiss = { showReadme = false })
    }
}

@Composable
private fun ShareHeader(share: Share, siteMismatch: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(share.name ?: share.id, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            share.owner?.let { owner ->
                Text(
                    stringResource(R.string.share_owner, owner.nickname.ifBlank { owner.id }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val details = listOfNotNull(
                pluralStringResource(R.plurals.info_views, share.visited, share.visited),
                share.expires?.let { stringResource(R.string.info_share_expires, formatDateTime(it)) },
                share.remainDownloads?.let { pluralStringResource(R.plurals.share_remain_downloads, it, it) },
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (siteMismatch) {
                Text(stringResource(R.string.share_site_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PasswordForm(state: ShareVisitState, onSubmit: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (password.isNotEmpty() && !state.submittingPassword) onSubmit(password) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.share?.let { ShareHeader(it, state.siteMismatch) }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.share_password_required), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.share_password)) },
                singleLine = true,
                isError = state.passwordIncorrect,
                supportingText = if (state.passwordIncorrect) {
                    { Text(stringResource(R.string.error_incorrect_password)) }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = submit, enabled = password.isNotEmpty() && !state.submittingPassword, modifier = Modifier.fillMaxWidth()) {
                if (state.submittingPassword) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
    }
}

@Composable
private fun ShareListing(
    state: ShareVisitState,
    navigator: FileNavigator,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val single = state.singleFile
    val files = if (single != null) listOf(single) else state.files
    LoadMoreEffect(listState, files.size, state.hasMore, onLoadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        val share = state.share
        if (share != null && state.isRoot) {
            item(key = "header", contentType = "header") { ShareHeader(share, state.siteMismatch) }
        }
        if (!state.isRoot) {
            item(key = "crumbs", contentType = "crumbs") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        state.crumbs.joinToString(" / ") { it.name.ifEmpty { share?.name ?: "" } },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
        if (state.loadingList && files.isEmpty()) {
            item(key = "loading", contentType = "loading") { LoadingMore() }
        } else if (files.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    stringResource(R.string.folder_empty),
                    modifier = Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(files, key = { it.path }, contentType = { if (it.isFolder) "folder" else "file" }) { file ->
            FileRow(
                file = file,
                loader = null,
                inTrash = false,
                selecting = false,
                selected = false,
                onClick = {
                    if (file.isFolder) onOpen(file.path) else navigator.openFile(file, state.currentUri ?: file.path)
                },
                onLongClick = { navigator.showInfo(file) },
                onMore = null,
            )
        }
        if (state.loadingMore) item(key = "more", contentType = "more") { LoadingMore() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadmeSheet(state: ShareVisitState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(state.readme?.name ?: stringResource(R.string.share_readme), style = MaterialTheme.typography.titleMedium)
            val content = state.readmeContent
            when {
                state.readmeLoading -> LoadingMore()
                content == null -> Text(
                    stringResource(R.string.share_readme_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                state.readme?.extension == "md" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    // 库默认 h1 用 displayLarge，在底部弹层里过大；按 M3 标题层级缩小
                    Markdown(
                        content = content,
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.headlineMedium,
                            h2 = MaterialTheme.typography.headlineSmall,
                            h3 = MaterialTheme.typography.titleLarge,
                            h4 = MaterialTheme.typography.titleMedium,
                            h5 = MaterialTheme.typography.titleSmall,
                            h6 = MaterialTheme.typography.labelLarge,
                        ),
                    )
                }
                else -> Text(
                    content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp),
                )
            }
        }
    }
}
