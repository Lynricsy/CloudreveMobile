package plus.ling.opencloudreve.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.Account
import plus.ling.opencloudreve.core.di.DataRepositories
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.SiteExplorer
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.ui.common.LocalImmersiveState
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import plus.ling.opencloudreve.ui.transfer.UploadDialog
import plus.ling.opencloudreve.ui.transfer.UploadRoute
import plus.ling.opencloudreve.ui.viewer.ViewerHostScreen
import plus.ling.opencloudreve.ui.viewer.ViewerRoute
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskMode
import plus.ling.opencloudreve.ui.share.ShareCreateRoute
import plus.ling.opencloudreve.ui.share.ShareEditDialog
import plus.ling.opencloudreve.ui.workflow.ArchiveTaskDialog
import plus.ling.opencloudreve.ui.workflow.ArchiveTaskRoute
import plus.ling.opencloudreve.ui.workflow.RemoteDownloadDialog
import plus.ling.opencloudreve.ui.workflow.RemoteDownloadRoute

/** 当前账户的用户资料（固定项、用户组权限），供没有自带用户状态的页面（搜索）计算动作规则 */
val LocalAccountUser = compositionLocalOf<User?> { null }

/** 只展开列表窗格（ListDetail 的 primary = 详情、secondary = 列表、tertiary = 附加） */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val ListPaneOnly = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Hidden,
    secondary = PaneAdaptedValue.Expanded,
    tertiary = PaneAdaptedValue.Hidden,
)

private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * “文件”Tab：`ListDetailPaneScaffold`——左侧（手机为全屏）是嵌套导航（首页/目录/搜索/分享浏览/目录选择器），
 * 右侧详情窗格显示文件信息（手机上点开后全屏，返回关闭）。
 *
 * @param openRequest 其它 Tab 请求在文件 Tab 打开的目录 URI（如管理页“打开此用户的文件”），处理后调用 [onOpenRequestConsumed]
 * @param shareRequest 外部入口（系统分享菜单）请求打开的分享链接，处理后调用 [onShareRequestConsumed]
 */
@Composable
fun FilesTab(
    account: Account,
    openRequest: String?,
    onOpenRequestConsumed: () -> Unit,
    shareRequest: String? = null,
    onShareRequestConsumed: () -> Unit = {},
) {
    key(account.id) {
        FilesTabContent(account, openRequest, onOpenRequestConsumed, shareRequest, onShareRequestConsumed)
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun FilesTabContent(
    account: Account,
    openRequest: String?,
    onOpenRequestConsumed: () -> Unit,
    shareRequest: String?,
    onShareRequestConsumed: () -> Unit,
    repositories: DataRepositories = koinInject(),
    site: SiteConfigRepository = koinInject(),
) {
    val accountId = account.id
    val navController = rememberNavController()
    val pane = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val features = LocalFileFeatures.current
    val user by remember(accountId) { repositories.userSettings(accountId).user }.collectAsStateWithLifecycle()
    val icons by produceState(FileIconTheme(FileIconTheme.DefaultIcons, account.siteUrl), accountId) {
        val explorer: SiteExplorer? = try {
            site.explorer(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            site.cachedExplorer(accountId)
        }
        val parsed = explorer?.parsedIcons().orEmpty()
        if (parsed.isNotEmpty()) value = FileIconTheme(parsed, account.siteUrl)
    }
    val singlePane = pane.scaffoldDirective.maxHorizontalPartitions == 1

    val navigator = remember(navController, pane, scope, features) {
        object : FileNavigator {
            override fun openFolder(uri: String, title: String?) = navController.navigate(FolderRoute(uri, title))

            override fun openFile(file: FileItem, listingUri: String) {
                val preview = features.preview
                if (preview != null) {
                    preview.run(FileHookContext(accountId, listingUri, navController, this), listOf(file))
                } else {
                    showInfo(file)
                }
            }

            override fun showInfo(file: FileItem) {
                scope.launch { pane.navigateTo(ListDetailPaneScaffoldRole.Detail, file.linkedUri) }
            }

            override fun openSearch(baseUri: String) = navController.navigate(FileSearchRoute(baseUri))

            override fun openShareLink(link: String) = navController.navigate(ShareVisitRoute(link))

            override fun pickFolder(request: FolderPickRequest) =
                navController.navigate(FolderPickerRoute(routeJson.encodeToString(FolderPickRequest.serializer(), request)))

            override fun back() {
                navController.popBackStack()
            }
        }
    }

    val consumeOpen by rememberUpdatedState(onOpenRequestConsumed)
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            navController.navigate(FolderRoute(openRequest))
            consumeOpen()
        }
    }
    val consumeShare by rememberUpdatedState(onShareRequestConsumed)
    LaunchedEffect(shareRequest) {
        if (shareRequest != null) {
            navController.navigate(ShareVisitRoute(shareRequest))
            consumeShare()
        }
    }
    // 版本/元数据页关闭后信息页刷新（[infoRevision]）；手机单窗格时打开工具页会先关闭详情，关闭工具页后重新打开该文件的信息页
    var infoRevision by remember { mutableIntStateOf(0) }
    var toolReturnKey by rememberSaveable { mutableStateOf<String?>(null) }
    val openTool: (String, Any) -> Unit = { infoKey, route ->
        scope.launch {
            if (singlePane) {
                toolReturnKey = infoKey
                pane.navigateBack()
            }
            navController.navigate(route)
        }
    }
    val onToolClosed: () -> Unit = {
        infoRevision++
        val back = toolReturnKey
        toolReturnKey = null
        if (back != null) scope.launch { pane.navigateTo(ListDetailPaneScaffoldRole.Detail, back) }
    }

    // 预览器沉浸时（平板）只显示列表侧窗格（预览器在其中），不改变详情窗格的导航状态，退出后恢复双栏
    val immersive = LocalImmersiveState.current.active
    CompositionLocalProvider(LocalFileIconTheme provides icons, LocalAccountUser provides user) {
        ListDetailPaneScaffold(
            directive = if (immersive) pane.scaffoldDirective.copy(maxHorizontalPartitions = 1) else pane.scaffoldDirective,
            value = if (immersive) ListPaneOnly else pane.scaffoldValue,
            listPane = {
                AnimatedPane {
                    FilesNavHost(accountId, navController, navigator, onToolClosed)
                }
            },
            detailPane = {
                AnimatedPane {
                    val key = pane.currentDestination?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }?.contentKey
                    if (key == null) {
                        FileInfoPlaceholder()
                    } else {
                        val close: () -> Unit = { scope.launch { pane.navigateBack() } }
                        // 手机单窗格时详情覆盖列表：系统返回关闭详情（注册晚于列表导航，优先级更高）
                        BackHandler(enabled = singlePane, onBack = close)
                        FileInfoScreen(
                            accountId = accountId,
                            uri = key,
                            onClose = close,
                            revision = infoRevision,
                            onOpenVersions = { uri -> openTool(key, VersionsRoute(uri)) },
                            onEditMetadata = { uri -> openTool(key, MetadataEditRoute(listOf(uri))) },
                        )
                    }
                }
            },
        )
    }
}

/** @param onToolClosed 版本/元数据页关闭（信息页需刷新） */
@Composable
private fun FilesNavHost(
    accountId: String,
    navController: NavHostController,
    navigator: FileNavigator,
    onToolClosed: () -> Unit,
) {
    NavHost(navController = navController, startDestination = FilesHomeRoute) {
        composable<FilesHomeRoute> {
            FilesHomeScreen(accountId = accountId, navigator = navigator, navController = navController)
        }
        composable<FolderRoute> { entry ->
            val route = entry.toRoute<FolderRoute>()
            val result = entry.pickResult()
            FileListScreen(
                accountId = accountId,
                uri = route.uri,
                navigator = navigator,
                navController = navController,
                canGoBack = navController.previousBackStackEntry != null,
                pickResult = result,
                onPickResultConsumed = { entry.savedStateHandle.remove<String>(FOLDER_PICK_RESULT_KEY) },
                titleOverride = route.title,
            )
        }
        composable<FileSearchRoute> { entry ->
            SearchScreen(
                accountId = accountId,
                baseUri = entry.toRoute<FileSearchRoute>().baseUri,
                navigator = navigator,
                navController = navController,
            )
        }
        composable<ShareVisitRoute> { entry ->
            ShareVisitScreen(
                accountId = accountId,
                link = entry.toRoute<ShareVisitRoute>().link,
                navigator = navigator,
                pickResult = entry.pickResult(),
                onPickResultConsumed = { entry.savedStateHandle.remove<String>(FOLDER_PICK_RESULT_KEY) },
            )
        }
        composable<ViewerRoute> { entry ->
            ViewerHostScreen(
                accountId = accountId,
                route = entry.toRoute<ViewerRoute>(),
                navigator = navigator,
                onClose = { navController.popBackStack() },
            )
        }
        dialog<FolderPickerRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
            val request = remember(entry) {
                routeJson.decodeFromString(FolderPickRequest.serializer(), entry.toRoute<FolderPickerRoute>().request)
            }
            FolderPickerScreen(
                accountId = accountId,
                initialUri = request.initialUri,
                excluded = request.excluded,
                // 移动时不能选项目所在的原目录
                sameFolders = if (request.purpose == FolderPickPurpose.Move) {
                    request.sources.mapNotNull { CrUri.parseOrNull(it)?.parent()?.toString() }
                } else {
                    emptyList()
                },
                confirmLabel = stringResource(
                    when (request.purpose) {
                        FolderPickPurpose.Move -> R.string.picker_move_here
                        FolderPickPurpose.Copy -> R.string.picker_copy_here
                        FolderPickPurpose.SaveShare -> R.string.picker_save_here
                    },
                ),
                onPicked = { destination ->
                    val result = FolderPickResult(request.purpose, request.sources, destination)
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        FOLDER_PICK_RESULT_KEY,
                        routeJson.encodeToString(FolderPickResult.serializer(), result),
                    )
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }
        dialog<UploadRoute> { entry ->
            val route = entry.toRoute<UploadRoute>()
            UploadDialog(
                accountId = accountId,
                folderUri = route.folderUri,
                media = route.media,
                onDismiss = { navController.popBackStack(entry.destination.id, inclusive = true) },
            )
        }
        dialog<ShareCreateRoute> { entry ->
            ShareEditDialog(
                accountId = accountId,
                fileUri = entry.toRoute<ShareCreateRoute>().fileUri,
                onDismiss = { navController.popBackStack(entry.destination.id, inclusive = true) },
            )
        }
        dialog<ArchiveTaskRoute> { entry ->
            val route = entry.toRoute<ArchiveTaskRoute>()
            ArchiveTaskDialog(
                accountId = accountId,
                mode = ArchiveTaskMode.valueOf(route.mode),
                srcUris = route.srcUris,
                dstUri = route.dstUri,
                initialArchiveName = route.archiveName,
                onDismiss = { navController.popBackStack(entry.destination.id, inclusive = true) },
            )
        }
        dialog<RemoteDownloadRoute> { entry ->
            val route = entry.toRoute<RemoteDownloadRoute>()
            val close = { navController.popBackStack(entry.destination.id, inclusive = true) }
            RemoteDownloadDialog(
                accountId = accountId,
                dstUri = route.dstUri,
                torrentUri = route.torrentUri,
                onDismiss = { close() },
                onCreated = { close() },
            )
        }
        composable<VersionsRoute> { entry ->
            val close = {
                navController.popBackStack()
                onToolClosed()
            }
            BackHandler(onBack = close)
            VersionsScreen(
                accountId = accountId,
                uri = entry.toRoute<VersionsRoute>().uri,
                onClose = close,
                onPreview = { file, version -> navController.navigate(ViewerRoute(file, version = version)) },
            )
        }
        composable<MetadataEditRoute> { entry ->
            val close = {
                navController.popBackStack()
                onToolClosed()
            }
            BackHandler(onBack = close)
            MetadataEditScreen(
                accountId = accountId,
                uris = entry.toRoute<MetadataEditRoute>().uris,
                onClose = close,
            )
        }
    }
}

/** 读取并解析目录选择器交回的结果 */
@Composable
private fun NavBackStackEntry.pickResult(): FolderPickResult? {
    val raw by savedStateHandle.getStateFlow<String?>(FOLDER_PICK_RESULT_KEY, null).collectAsStateWithLifecycle()
    return remember(raw) { raw?.let { routeJson.decodeFromString(FolderPickResult.serializer(), it) } }
}
