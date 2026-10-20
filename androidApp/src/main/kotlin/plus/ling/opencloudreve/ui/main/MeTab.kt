package plus.ling.opencloudreve.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.auth.AccountItem
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherIntent
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherUiState
import plus.ling.opencloudreve.ui.account.AccountAvatar
import plus.ling.opencloudreve.ui.account.SignOutDialog

/** “我的”Tab：顶部当前账户卡（点按打开切换器）+ 账户操作（设置项由后续阶段补充） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeTab(
    state: AccountSwitcherUiState,
    onIntent: (AccountSwitcherIntent) -> Unit,
    onOpenSwitcher: () -> Unit,
    onAddAccount: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf<AccountItem?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_me)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            state.active?.let { active ->
                AccountCard(active, onOpenSwitcher)
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenSwitcher),
                leadingContent = { Icon(Icons.Outlined.SwitchAccount, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.account_switch)) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onAddAccount),
                leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.account_add)) },
            )
            state.active?.let { active ->
                ListItem(
                    modifier = Modifier.clickable { confirmSignOut = active },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error)
                    },
                )
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
private fun AccountCard(item: AccountItem, onClick: () -> Unit) {
    val account = item.account
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountAvatar(item, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.nickname.ifBlank { account.email },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.SwitchAccount, contentDescription = stringResource(R.string.account_switch))
        }
    }
}
