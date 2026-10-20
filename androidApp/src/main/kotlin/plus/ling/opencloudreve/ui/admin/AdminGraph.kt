package plus.ling.opencloudreve.ui.admin

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** 管理入口（概览 + 各子页入口） */
@Serializable
data object AdminHomeRoute

@Serializable
data object AdminUsersRoute

@Serializable
data class AdminUserEditRoute(val userId: Int)

@Serializable
data object AdminSharesRoute

@Serializable
data object AdminFilesRoute

@Serializable
data object AdminQueueRoute

@Serializable
data object AdminNodesRoute

/** 用户编辑页保存/删除后写入用户列表 savedStateHandle 的刷新标记 */
private const val USERS_DIRTY_KEY = "admin_users_dirty"

/** 在宿主 NavHost 中注册全部管理目的地；宿主用 navController.navigate(AdminHomeRoute) 进入 */
fun NavGraphBuilder.adminGraph(navController: NavHostController, accountId: String, openFilesAt: (uri: String) -> Unit) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<AdminHomeRoute> {
        AdminHomeScreen(accountId = accountId, onBack = back, onNavigate = { navController.navigate(it) })
    }
    composable<AdminUsersRoute> { entry ->
        val handle = entry.savedStateHandle
        val dirty by handle.getStateFlow(USERS_DIRTY_KEY, false).collectAsStateWithLifecycle()
        AdminUsersScreen(
            accountId = accountId,
            refreshRequested = dirty,
            onRefreshConsumed = { handle[USERS_DIRTY_KEY] = false },
            onBack = back,
            onOpenUser = { navController.navigate(AdminUserEditRoute(it)) },
        )
    }
    composable<AdminUserEditRoute> { entry ->
        val route = entry.toRoute<AdminUserEditRoute>()
        AdminUserEditScreen(
            accountId = accountId,
            userId = route.userId,
            onBack = back,
            onChanged = { navController.previousBackStackEntry?.savedStateHandle?.set(USERS_DIRTY_KEY, true) },
            openFilesAt = openFilesAt,
        )
    }
    composable<AdminSharesRoute> { AdminSharesScreen(accountId = accountId, onBack = back) }
    composable<AdminFilesRoute> { AdminFilesScreen(accountId = accountId, onBack = back) }
    composable<AdminQueueRoute> { AdminQueueScreen(accountId = accountId, onBack = back) }
    composable<AdminNodesRoute> { AdminNodesScreen(accountId = accountId, onBack = back) }
}
