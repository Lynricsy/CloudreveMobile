package plus.ling.opencloudreve.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
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
import plus.ling.opencloudreve.core.site.SiteConfigRepository

/** 当前账户的用户资料（固定项、用户组权限），供没有自带用户状态的页面（搜索）计算动作规则 */
val LocalAccountUser = compositionLocalOf<User?> { null }

private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * “文件”Tab：`ListDetailPaneScaffold`——左侧（手机为全屏）是嵌套导航（首页/目录/搜索/分享浏览/目录选择器），
 * 右侧详情窗格显示文件信息（手机上点开后全屏，返回关闭）。
 *
 * @param openRequest 其它 Tab 请求在文件 Tab 打开的目录 URI（如管理页“打开此用户的文件”），处理后调用 [onOpenRequestConsumed]
 */
@Composable
fun FilesTab(account: Account, openRequest: String?, onOpenRequestConsumed: () -> Unit) {
    key(account.id) {
        FilesTabContent(account, openRequest, onOpenRequestConsumed)
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun FilesTabContent(
    account: Account,
    openRequest: String?,
    onOpenRequestConsumed: () -> Unit,
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
            override fun openFolder(uri: String) = navController.navigate(FolderRoute(uri))

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

    CompositionLocalProvider(LocalFileIconTheme provides icons, LocalAccountUser provides user) {
        ListDetailPaneScaffold(
            directive = pane.scaffoldDirective,
            value = pane.scaffoldValue,
            listPane = {
                AnimatedPane {
                    FilesNavHost(accountId, navController, navigator)
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
                        FileInfoScreen(accountId = accountId, uri = key, onClose = close)
                    }
                }
            },
        )
    }
}

@Composable
private fun FilesNavHost(accountId: String, navController: NavHostController, navigator: FileNavigator) {
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
        dialog<FolderPickerRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
            val request = remember(entry) {
                routeJson.decodeFromString(FolderPickRequest.serializer(), entry.toRoute<FolderPickerRoute>().request)
            }
            FolderPickerScreen(
                accountId = accountId,
                initialUri = request.initialUri,
                excluded = request.excluded,
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
    }
}

/** 读取并解析目录选择器交回的结果 */
@Composable
private fun NavBackStackEntry.pickResult(): FolderPickResult? {
    val raw by savedStateHandle.getStateFlow<String?>(FOLDER_PICK_RESULT_KEY, null).collectAsStateWithLifecycle()
    return remember(raw) { raw?.let { routeJson.decodeFromString(FolderPickResult.serializer(), it) } }
}
