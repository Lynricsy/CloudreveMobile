package plus.ling.opencloudreve.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.koin.compose.koinInject
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.ui.auth.LoginScreen
import plus.ling.opencloudreve.ui.auth.ServerSetupScreen
import plus.ling.opencloudreve.ui.main.MainScreen

/**
 * 根导航：
 * - 无当前账户 → 服务器地址页 → 登录页；
 * - 当前账户会话失效 → 该站点的登录页（预填邮箱）；
 * - 否则 → 主界面。
 * 登录成功清空返回栈进入主界面；在主界面登出最后一个账户时回到服务器地址页。
 */
@Composable
fun AppNavHost(accounts: AccountRepository = koinInject()) {
    val navController = rememberNavController()
    val active by accounts.activeAccount.collectAsStateWithLifecycle()
    val start: Any = remember {
        val a = accounts.activeAccount.value
        when {
            a == null -> ServerSetupRoute
            a.sessionExpired -> LoginRoute(a.siteUrl, a.email)
            else -> MainRoute
        }
    }

    // 账户状态变化（登出、被动失效、切换到失效账户）时重定向
    LaunchedEffect(active?.id, active?.sessionExpired) {
        val a = active
        val destination = navController.currentBackStackEntry?.destination
        val onMain = destination?.hasRoute<MainRoute>() == true
        val onLogin = destination?.hasRoute<LoginRoute>() == true
        when {
            a == null && onMain -> navController.resetTo(ServerSetupRoute)
            a != null && a.sessionExpired && !onLogin -> navController.resetTo(LoginRoute(a.siteUrl, a.email))
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable<ServerSetupRoute> {
            ServerSetupScreen(
                canGoBack = navController.previousBackStackEntry != null,
                onBack = { navController.popBackStack() },
                onProceed = { siteUrl -> navController.navigate(LoginRoute(siteUrl)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                siteUrl = route.siteUrl,
                email = route.email,
                canGoBack = navController.previousBackStackEntry != null,
                onBack = { navController.popBackStack() },
                onSignedIn = { navController.resetTo(MainRoute) },
                onChangeServer = { navController.resetTo(ServerSetupRoute) },
            )
        }
        composable<MainRoute> {
            MainScreen(onAddAccount = { navController.navigate(ServerSetupRoute) })
        }
    }
}

/** 清空返回栈后导航到 [route] */
private fun NavHostController.resetTo(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
