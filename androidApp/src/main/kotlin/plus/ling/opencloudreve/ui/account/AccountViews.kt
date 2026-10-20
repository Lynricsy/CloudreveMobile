package plus.ling.opencloudreve.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.auth.AccountItem
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherIntent
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherUiState
import plus.ling.opencloudreve.ui.common.message

/** 头像：加载失败（无头像 404）时显示昵称首字母 */
@Composable
fun AccountAvatar(item: AccountItem, size: Dp, modifier: Modifier = Modifier) {
    val name = item.account.nickname.ifBlank { item.account.email }
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = if (size >= 56.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
    SubcomposeAsyncImage(
        model = item.avatarUrl,
        contentDescription = stringResource(R.string.account_avatar_desc),
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
        loading = { fallback() },
        error = { fallback() },
    )
}

/** 账户行：头像、昵称、邮箱 @ 主机、状态 */
@Composable
fun AccountRow(
    item: AccountItem,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null,
) {
    val account = item.account
    ListItem(
        modifier = if (onClick != null) Modifier.clickableRow(onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AccountAvatar(item, 40.dp) },
        headlineContent = {
            Text(account.nickname.ifBlank { account.email }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text("${account.email} · ${account.host}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account.sessionExpired) {
                    Text(stringResource(R.string.account_expired), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = trailing,
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/**
 * 账户切换器底部弹层：切换、登出（确认）、添加账户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    state: AccountSwitcherUiState,
    onIntent: (AccountSwitcherIntent) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf<AccountItem?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "title", contentType = "title") {
                Text(
                    text = stringResource(R.string.account_switch),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(state.accounts, key = { it.account.id }, contentType = { "account" }) { item ->
                AccountRow(
                    item = item,
                    onClick = {
                        onIntent(AccountSwitcherIntent.SwitchTo(item.account.id))
                        onDismiss()
                    },
                    trailing = {
                        when {
                            state.signingOutId == item.account.id ->
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.isActive) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(R.string.account_current),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { confirmSignOut = item }) {
                                    Icon(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.account_sign_out))
                                }
                            }
                        }
                    },
                )
            }
            item(key = "divider", contentType = "divider") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item(key = "add", contentType = "action") {
                ListItem(
                    modifier = Modifier.clickableRow {
                        onDismiss()
                        onAddAccount()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.account_add)) },
                )
            }
            state.error?.let { error ->
                item(key = "error", contentType = "error") {
                    Text(
                        text = error.message(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    confirmSignOut?.let { item ->
        SignOutDialog(
            item = item,
            onConfirm = {
                confirmSignOut = null
                onIntent(AccountSwitcherIntent.SignOut(item.account.id))
            },
            onDismiss = { confirmSignOut = null },
        )
    }
}

@Composable
fun SignOutDialog(item: AccountItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_sign_out_title)) },
        text = { Text(stringResource(R.string.account_sign_out_message, "${item.account.email} @ ${item.account.host}")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.account_sign_out)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
