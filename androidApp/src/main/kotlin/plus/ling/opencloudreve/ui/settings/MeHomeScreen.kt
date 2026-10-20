package plus.ling.opencloudreve.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.auth.AccountItem
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherIntent
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherUiState
import plus.ling.opencloudreve.core.presentation.settings.SettingsEffect
import plus.ling.opencloudreve.core.presentation.settings.SettingsField
import plus.ling.opencloudreve.core.presentation.settings.SettingsIntent
import plus.ling.opencloudreve.core.presentation.settings.SettingsState
import plus.ling.opencloudreve.ui.account.AccountAvatar
import plus.ling.opencloudreve.ui.account.SignOutDialog
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.formatBytes
import kotlinx.coroutines.flow.Flow

/**
 * “我的”首页：个人资料卡（头像、昵称、邮箱、用户组、容量）+ 设置/管理入口 + 账户操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeHomeScreen(
    state: SettingsState,
    effects: Flow<SettingsEffect>,
    onIntent: (SettingsIntent) -> Unit,
    switcher: AccountSwitcherUiState,
    onSwitcherIntent: (AccountSwitcherIntent) -> Unit,
    onOpenSwitcher: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAvatarSheet by rememberSaveable { mutableStateOf(false) }
    var cropUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var editingNickname by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf<AccountItem?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropUri = uri
    }

    plus.ling.opencloudreve.ui.common.CollectEffects(effects) { effect ->
        when (effect) {
            is SettingsEffect.Saved -> when (effect.field) {
                SettingsField.Avatar -> scope.snack(snackbar, resources.getString(R.string.me_avatar_updated))
                SettingsField.Nickname -> scope.snack(snackbar, resources.getString(R.string.me_nickname_updated))
                else -> Unit
            }
            is SettingsEffect.Failed -> scope.snack(snackbar, effect.error.message(context))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_me)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.user != null,
            onRefresh = { onIntent(SettingsIntent.Refresh) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                switcher.active?.let { active ->
                    ProfileCard(
                        item = active,
                        state = state,
                        onAvatarClick = { showAvatarSheet = true },
                        onEditNickname = { editingNickname = true },
                    )
                }
                state.error?.takeIf { state.user == null }?.let { LoadError(it) { onIntent(SettingsIntent.Refresh) } }

                SettingRow(
                    title = stringResource(R.string.settings_title),
                    icon = Icons.Outlined.Settings,
                    onClick = onOpenSettings,
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
                if (state.user?.isAdmin == true) {
                    SettingRow(
                        title = stringResource(R.string.me_admin),
                        subtitle = stringResource(R.string.me_admin_desc),
                        icon = Icons.Outlined.AdminPanelSettings,
                        onClick = onOpenAdmin,
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingRow(
                    title = stringResource(R.string.account_switch),
                    icon = Icons.Outlined.SwitchAccount,
                    onClick = onOpenSwitcher,
                )
                SettingRow(
                    title = stringResource(R.string.account_add),
                    icon = Icons.Outlined.PersonAdd,
                    onClick = onAddAccount,
                )
                switcher.active?.let { active ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = switcher.signingOutId == null) { confirmSignOut = active },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        headlineContent = { Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error) },
                        trailingContent = if (switcher.signingOutId == active.account.id) {
                            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (showAvatarSheet) {
        ModalBottomSheet(onDismissRequest = { showAvatarSheet = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.me_avatar_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SettingRow(
                    title = stringResource(R.string.me_avatar_pick),
                    icon = Icons.Outlined.PhotoLibrary,
                    onClick = {
                        showAvatarSheet = false
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                SettingRow(
                    title = stringResource(R.string.me_avatar_gravatar),
                    subtitle = stringResource(R.string.me_avatar_gravatar_desc),
                    icon = Icons.Outlined.Face,
                    onClick = {
                        showAvatarSheet = false
                        onIntent(SettingsIntent.UseGravatar)
                    },
                )
            }
        }
    }

    cropUri?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onCropped = { bytes, mime ->
                cropUri = null
                onIntent(SettingsIntent.UploadAvatar(bytes, mime))
            },
            onDismiss = { cropUri = null },
        )
    }

    if (editingNickname) {
        NicknameDialog(
            initial = state.nickname,
            onConfirm = {
                editingNickname = false
                onIntent(SettingsIntent.SetNickname(it))
            },
            onDismiss = { editingNickname = false },
        )
    }

    confirmSignOut?.let { item ->
        SignOutDialog(
            item = item,
            onConfirm = {
                confirmSignOut = null
                onSwitcherIntent(AccountSwitcherIntent.SignOut(item.account.id))
            },
            onDismiss = { confirmSignOut = null },
        )
    }
}

@Composable
private fun ProfileCard(
    item: AccountItem,
    state: SettingsState,
    onAvatarClick: () -> Unit,
    onEditNickname: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val account = item.account
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AccountAvatar(
                    item,
                    72.dp,
                    modifier = Modifier.clip(CircleShape).clickable(
                        onClickLabel = stringResource(R.string.me_avatar_title),
                        onClick = onAvatarClick,
                    ),
                )
                if (state.saving == SettingsField.Avatar) CircularProgressIndicator(Modifier.size(72.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.nickname.ifBlank { account.nickname.ifBlank { account.email } },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.saving == SettingsField.Nickname) {
                        CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onEditNickname, enabled = state.user != null) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.me_nickname_edit))
                        }
                    }
                }
                Text(
                    text = state.email.ifBlank { account.email },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(state.groupName, account.host).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.capacity?.let { cap ->
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                LinearProgressIndicator(
                    progress = { state.capacityUsedFraction.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (state.overQuota) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.me_capacity, formatBytes(context, cap.used), formatBytes(context, cap.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.overQuota) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NicknameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.me_nickname_edit)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(255) },
                singleLine = true,
                label = { Text(stringResource(R.string.me_nickname)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank() && value.trim() != initial) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
