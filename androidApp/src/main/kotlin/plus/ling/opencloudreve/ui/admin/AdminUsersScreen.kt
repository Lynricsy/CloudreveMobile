package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminConditions
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditState
import plus.ling.opencloudreve.core.presentation.admin.AdminUsersViewModel

/**
 * 用户列表：按邮箱/昵称/状态/用户组筛选，点按进入编辑页。
 * [refreshRequested] 为编辑页保存/删除后留下的标记，消费后刷新列表。
 */
@Composable
internal fun AdminUsersScreen(
    accountId: String,
    refreshRequested: Boolean,
    onRefreshConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenUser: (Int) -> Unit,
) {
    val vm: AdminUsersViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val consumed by rememberUpdatedState(onRefreshConsumed)

    CollectAdminListEffects(vm.effects, snackbar)
    LaunchedEffect(refreshRequested) {
        if (refreshRequested) {
            consumed()
            vm.on(AdminListIntent.Refresh)
        }
    }

    val statusOptions = AdminUserEditState.STATUSES.map { it to userStatusLabel(it) }
    AdminListScaffold(
        title = stringResource(R.string.admin_users),
        state = state,
        onIntent = vm::on,
        itemKey = { it.id },
        supportsDelete = vm.supportsDelete,
        snackbar = snackbar,
        onBack = onBack,
        onItemClick = { onOpenUser(it.id) },
        filters = {
            val c = state.conditions
            TextFilterChip(stringResource(R.string.admin_user_email), c[AdminConditions.USER_EMAIL]) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.USER_EMAIL, it))
            }
            TextFilterChip(stringResource(R.string.admin_user_nick), c[AdminConditions.USER_NICK]) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.USER_NICK, it))
            }
            OptionFilterChip(stringResource(R.string.admin_user_status), c[AdminConditions.USER_STATUS], statusOptions) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.USER_STATUS, it))
            }
            OptionFilterChip(
                stringResource(R.string.admin_user_group),
                c[AdminConditions.USER_GROUP],
                groups.map { it.id.toString() to it.name },
            ) { vm.on(AdminListIntent.SetCondition(AdminConditions.USER_GROUP, it)) }
        },
    ) { user ->
        val groupName = user.edges.group?.name ?: groups.firstOrNull { it.id == user.groupUsers }?.name ?: EMPTY_VALUE
        AdminListItem(
            headline = user.nick.ifEmpty { user.email },
            supporting = "${user.email}\n$groupName",
            trailing = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(userStatusLabel(user.status), userStatusTone(user.status))
                    Text(formatBytes(user.storage), style = MaterialTheme.typography.labelSmall)
                }
            },
        )
    }
}
