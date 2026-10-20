package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminConditions
import plus.ling.opencloudreve.core.model.AdminShare
import plus.ling.opencloudreve.core.presentation.admin.AdminListIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminSharesViewModel
import kotlin.time.Clock

/** 分享列表：按用户 id / 文件 id 筛选，详情面板可复制链接、删除 */
@Composable
internal fun AdminSharesScreen(accountId: String, onBack: () -> Unit) {
    val vm: AdminSharesViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val copy = rememberAdminCopy(snackbar)
    var confirmDelete by remember { mutableStateOf<Int?>(null) }

    CollectAdminListEffects(vm.effects, snackbar)

    AdminListScaffold(
        title = stringResource(R.string.admin_shares),
        state = state,
        onIntent = vm::on,
        itemKey = { it.id },
        supportsDelete = vm.supportsDelete,
        snackbar = snackbar,
        onBack = onBack,
        onItemClick = { vm.on(AdminListIntent.OpenDetail(it.id)) },
        filters = {
            val c = state.conditions
            TextFilterChip(stringResource(R.string.admin_filter_user_id), c[AdminConditions.SHARE_USER_ID], numeric = true) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.SHARE_USER_ID, it))
            }
            TextFilterChip(stringResource(R.string.admin_filter_file_id), c[AdminConditions.SHARE_FILE_ID], numeric = true) {
                vm.on(AdminListIntent.SetCondition(AdminConditions.SHARE_FILE_ID, it))
            }
        },
        detailSheet = { share ->
            ShareDetail(share, onCopy = copy, onDelete = { confirmDelete = share.id })
        },
    ) { share ->
        AdminListItem(
            headline = shareFileName(share),
            overline = ownerName(share),
            supporting = buildString {
                append(stringResource(R.string.admin_share_stats, share.views, share.downloads))
                append(" · ")
                append(expiryText(share))
            },
            trailing = if (!share.password.isNullOrEmpty()) {
                { Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.admin_share_has_password)) }
            } else {
                null
            },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.admin_share_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.on(AdminListIntent.Delete(listOf(id)))
                }) { Text(stringResource(R.string.admin_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun shareFileName(share: AdminShare): String =
    share.edges.file?.name?.ifEmpty { null } ?: stringResource(R.string.admin_share_file_missing)

@Composable
private fun ownerName(share: AdminShare): String =
    share.edges.user?.let { u -> u.nick.ifEmpty { u.email } } ?: EMPTY_VALUE

@Composable
private fun expiryText(share: AdminShare): String {
    val expires = share.expires ?: return stringResource(R.string.admin_share_never)
    return if (expires < Clock.System.now()) {
        stringResource(R.string.admin_share_expired_at, formatTime(expires))
    } else {
        stringResource(R.string.admin_share_expires_at, formatTime(expires))
    }
}

@Composable
private fun ShareDetail(share: AdminShare, onCopy: (String) -> Unit, onDelete: () -> Unit) {
    SectionTitle(shareFileName(share))
    InfoRow(stringResource(R.string.admin_field_id), share.id.toString())
    share.edges.file?.let { InfoRow(stringResource(R.string.admin_filter_file_id), it.id.toString()) }
    val owner = share.edges.user
    InfoRow(
        stringResource(R.string.admin_share_owner),
        owner?.let { "${it.nick.ifEmpty { EMPTY_VALUE }} <${it.email}> (#${it.id})" } ?: EMPTY_VALUE,
    )
    InfoRow(stringResource(R.string.admin_order_views), share.views.toString())
    InfoRow(stringResource(R.string.admin_order_downloads), share.downloads.toString())
    InfoRow(
        stringResource(R.string.admin_order_remain_downloads),
        share.remainDownloads?.toString() ?: stringResource(R.string.admin_share_unlimited),
    )
    InfoRow(stringResource(R.string.admin_share_expires), expiryText(share))
    InfoRow(
        stringResource(R.string.admin_share_password),
        share.password?.ifEmpty { null } ?: stringResource(R.string.admin_share_no_password),
        onCopy = if (!share.password.isNullOrEmpty()) onCopy else null,
    )
    share.props?.let { props ->
        InfoRow(stringResource(R.string.admin_share_view), stringResource(if (props.shareView) R.string.admin_yes else R.string.admin_no))
        InfoRow(stringResource(R.string.admin_share_readme), stringResource(if (props.showReadMe) R.string.admin_yes else R.string.admin_no))
    }
    InfoRow(stringResource(R.string.admin_field_created), formatTime(share.createdAt))
    InfoRow(stringResource(R.string.admin_field_updated), formatTime(share.updatedAt))
    val link = share.shareLink.orEmpty()
    InfoRow(stringResource(R.string.admin_share_link), link)
    SheetActions {
        if (link.isNotEmpty()) {
            OutlinedButton(onClick = { onCopy(link) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.admin_share_copy_link), Modifier.padding(start = 8.dp))
            }
        }
        OutlinedButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                Modifier.size(ButtonDefaults.IconSize),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.admin_delete), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}
