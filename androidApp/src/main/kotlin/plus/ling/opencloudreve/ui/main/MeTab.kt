package plus.ling.opencloudreve.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherIntent
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherUiState
import plus.ling.opencloudreve.core.presentation.settings.DavAccountsViewModel
import plus.ling.opencloudreve.core.presentation.settings.PoliciesViewModel
import plus.ling.opencloudreve.core.presentation.settings.SecurityViewModel
import plus.ling.opencloudreve.core.presentation.settings.SettingsViewModel
import plus.ling.opencloudreve.ui.admin.AdminHomeRoute
import plus.ling.opencloudreve.ui.admin.adminGraph
import plus.ling.opencloudreve.ui.files.FolderPickerScreen
import plus.ling.opencloudreve.ui.settings.AboutScreen
import plus.ling.opencloudreve.ui.settings.DavAccountsScreen
import plus.ling.opencloudreve.ui.settings.LicensesScreen
import plus.ling.opencloudreve.ui.settings.MeHomeScreen
import plus.ling.opencloudreve.ui.settings.PoliciesScreen
import plus.ling.opencloudreve.ui.settings.SecurityScreen
import plus.ling.opencloudreve.ui.settings.SettingsDestinations
import plus.ling.opencloudreve.ui.settings.SettingsScreen
import plus.ling.opencloudreve.ui.theme.ThemePreferences

/** “我的”首页 */
@Serializable
data object MeHomeRoute

@Serializable
data object SettingsRoute

@Serializable
data object SecurityRoute

@Serializable
data object DavAccountsRoute

/** WebDAV 根目录选择 */
@Serializable
data class DavRootPickerRoute(val initialUri: String)

@Serializable
data object PoliciesRoute

@Serializable
data object AboutRoute

@Serializable
data object LicensesRoute

/** 目录选择结果在 WebDAV 页 `SavedStateHandle` 中的键 */
private const val DAV_ROOT_KEY = "dav_root"

/**
 * “我的”Tab：内嵌导航（底栏保持可见）——首页、设置及其子页、管理。
 * @param onReLogin 修改密码后账户已登出，到该站点登录页重新登录
 * @param openFilesAt 在“文件”Tab 打开某个目录（管理端“打开此用户的文件”）
 */
@Composable
fun MeTab(
    accountId: String,
    switcherState: AccountSwitcherUiState,
    onSwitcherIntent: (AccountSwitcherIntent) -> Unit,
    onOpenSwitcher: () -> Unit,
    onAddAccount: () -> Unit,
    onReLogin: (siteUrl: String, email: String) -> Unit,
    openFilesAt: (uri: String) -> Unit,
) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = MeHomeRoute) {
        composable<MeHomeRoute> {
            val vm: SettingsViewModel = koinViewModel { parametersOf(accountId) }
            val state by vm.state.collectAsStateWithLifecycle()
            MeHomeScreen(
                state = state,
                effects = vm.effects,
                onIntent = vm::on,
                switcher = switcherState,
                onSwitcherIntent = onSwitcherIntent,
                onOpenSwitcher = onOpenSwitcher,
                onAddAccount = onAddAccount,
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenAdmin = { nav.navigate(AdminHomeRoute) },
            )
        }
        composable<SettingsRoute> {
            val vm: SettingsViewModel = koinViewModel { parametersOf(accountId) }
            val state by vm.state.collectAsStateWithLifecycle()
            val themePrefs: ThemePreferences = koinInject()
            val theme by themePrefs.settings.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                effects = vm.effects,
                onIntent = vm::on,
                theme = theme,
                themePrefs = themePrefs,
                nav = SettingsDestinations(
                    onBack = back,
                    onSecurity = { nav.navigate(SecurityRoute) },
                    onWebDav = { nav.navigate(DavAccountsRoute) },
                    onPolicies = { nav.navigate(PoliciesRoute) },
                    onAbout = { nav.navigate(AboutRoute) },
                ),
            )
        }
        composable<SecurityRoute> {
            val vm: SecurityViewModel = koinViewModel { parametersOf(accountId) }
            val state by vm.state.collectAsStateWithLifecycle()
            SecurityScreen(state = state, effects = vm.effects, onIntent = vm::on, onBack = back, onReLogin = onReLogin)
        }
        composable<DavAccountsRoute> { entry ->
            val vm: DavAccountsViewModel = koinViewModel { parametersOf(accountId) }
            val state by vm.state.collectAsStateWithLifecycle()
            val picked by entry.savedStateHandle.getStateFlow<String?>(DAV_ROOT_KEY, null).collectAsStateWithLifecycle()
            DavAccountsScreen(
                state = state,
                effects = vm.effects,
                onIntent = vm::on,
                onBack = back,
                onPickFolder = { nav.navigate(DavRootPickerRoute(it)) },
                pickedUri = picked,
                onPickedConsumed = { entry.savedStateHandle[DAV_ROOT_KEY] = null },
            )
        }
        composable<DavRootPickerRoute> { entry ->
            val route = entry.toRoute<DavRootPickerRoute>()
            FolderPickerScreen(
                accountId = accountId,
                initialUri = route.initialUri,
                excluded = emptyList(),
                confirmLabel = stringResource(R.string.dav_pick_root),
                onPicked = { uri ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(DAV_ROOT_KEY, uri)
                    nav.popBackStack()
                },
                onCancel = back,
            )
        }
        composable<PoliciesRoute> {
            val vm: PoliciesViewModel = koinViewModel { parametersOf(accountId) }
            val state by vm.state.collectAsStateWithLifecycle()
            PoliciesScreen(state = state, onIntent = vm::on, onBack = back)
        }
        composable<AboutRoute> {
            AboutScreen(accountId = accountId, onBack = back, onLicenses = { nav.navigate(LicensesRoute) })
        }
        composable<LicensesRoute> {
            LicensesScreen(onBack = back)
        }
        adminGraph(navController = nav, accountId = accountId, openFilesAt = openFilesAt)
    }
}
