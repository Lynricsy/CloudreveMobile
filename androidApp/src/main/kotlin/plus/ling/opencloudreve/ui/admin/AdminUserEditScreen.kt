package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.AdminUser
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditState
import plus.ling.opencloudreve.core.presentation.admin.AdminUserEditViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.openCustomTab

/**
 * 用户编辑：基本资料、状态/用户组、重置密码、清除两步验证、容量校准、浏览文件、删除。
 * 保存或删除成功后调用 [onChanged] 通知列表刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminUserEditScreen(
    accountId: String,
    userId: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    openFilesAt: (uri: String) -> Unit,
) {
    val vm: AdminUserEditViewModel = koinViewModel(key = "admin-user:$accountId:$userId") { parametersOf(accountId, userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            AdminUserEditEffect.Saved -> {
                onChanged()
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.admin_saved)) }
            }
            AdminUserEditEffect.Calibrated -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.admin_user_calibrated)) }
            AdminUserEditEffect.Deleted -> {
                onChanged()
                onBack()
            }
            is AdminUserEditEffect.OpenFiles -> openFilesAt(effect.uri)
            is AdminUserEditEffect.OpenUrl -> openCustomTab(context, effect.url)
            is AdminUserEditEffect.Failed -> scope.launch { snackbar.showSnackbar(adminErrorText(context, effect.error)) }
        }
    }

    Scaffold(
        contentWindowInsets = adminContentInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(state.user?.nick?.ifEmpty { null } ?: stringResource(R.string.admin_user_edit_title), maxLines = 1) },
                actions = {
                    OpenInWebAction(
                        handoff = state.handoff,
                        opening = state.openingWeb,
                        serverVersion = null,
                        snackbar = snackbar,
                        onOpen = { vm.on(AdminUserEditIntent.OpenInWeb) },
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val user = state.user
            val error = state.error
            when {
                user == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                user == null && error != null -> FullError(error) { vm.on(AdminUserEditIntent.Refresh) }
                user != null -> UserEditContent(state, user, vm::on, onDelete = { confirmDelete = true })
            }
        }
    }

    if (confirmDelete) {
        val name = state.user?.let { it.nick.ifEmpty { it.email } }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.admin_user_delete)) },
            text = { Text(stringResource(R.string.admin_user_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.on(AdminUserEditIntent.Delete)
                }) { Text(stringResource(R.string.admin_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun UserEditContent(
    state: AdminUserEditState,
    user: AdminUser,
    onIntent: (AdminUserEditIntent) -> Unit,
    onDelete: () -> Unit,
) {
    val form = state.form
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp),
    ) {
        if (state.loading || state.saving || state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.email,
                onValueChange = { onIntent(AdminUserEditIntent.SetEmail(it)) },
                label = { Text(stringResource(R.string.admin_user_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.nick,
                onValueChange = { onIntent(AdminUserEditIntent.SetNick(it)) },
                label = { Text(stringResource(R.string.admin_user_nick)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = stringResource(R.string.admin_user_status),
                selected = form.status,
                options = state.statusOptions.map { it to userStatusLabel(it) },
                enabled = !state.statusAndGroupLocked,
                onSelect = { onIntent(AdminUserEditIntent.SetStatus(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = stringResource(R.string.admin_user_group),
                selected = form.groupId,
                // 用户组列表未加载完时至少显示当前组名
                options = state.groups.map { it.id to it.name }.ifEmpty {
                    listOfNotNull(user.edges.group?.let { it.id to it.name })
                },
                enabled = !state.statusAndGroupLocked,
                onSelect = { onIntent(AdminUserEditIntent.SetGroup(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.statusAndGroupLocked) {
                Text(
                    stringResource(R.string.admin_user_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val passwordInvalid = form.newPassword.isNotEmpty() && form.newPassword.length !in 6..128
            OutlinedTextField(
                value = form.newPassword,
                onValueChange = { onIntent(AdminUserEditIntent.SetNewPassword(it)) },
                label = { Text(stringResource(R.string.admin_user_new_password)) },
                supportingText = { Text(stringResource(R.string.admin_user_password_hint)) },
                isError = passwordInvalid,
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (user.twoFaEnabled) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.admin_user_clear_2fa)) },
                supportingContent = { Text(stringResource(R.string.admin_user_clear_2fa_desc)) },
                trailingContent = {
                    Switch(checked = form.clearTwoFactor, onCheckedChange = { onIntent(AdminUserEditIntent.SetClearTwoFactor(it)) })
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(enabled = state.dirty && !state.saving, onClick = { onIntent(AdminUserEditIntent.ResetForm) }) {
                Text(stringResource(R.string.admin_discard))
            }
            Button(enabled = state.canSave, onClick = { onIntent(AdminUserEditIntent.Save) }) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.admin_save))
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 16.dp))
        SectionTitle(stringResource(R.string.admin_user_capacity))
        val capacity = user.capacity
        if (capacity != null) {
            InfoRow(
                stringResource(R.string.admin_user_capacity),
                stringResource(R.string.admin_user_capacity_value, formatBytes(capacity.used), formatBytes(capacity.total)),
            )
            if (capacity.total > 0) {
                LinearProgressIndicator(
                    progress = { (capacity.used.toFloat() / capacity.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else {
            InfoRow(stringResource(R.string.admin_user_storage), formatBytes(user.storage))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.calibrating, onClick = { onIntent(AdminUserEditIntent.Calibrate) }) {
                if (state.calibrating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.admin_user_calibrate))
                }
            }
            OutlinedButton(enabled = state.filesUri != null, onClick = { onIntent(AdminUserEditIntent.OpenFiles) }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.admin_user_open_files), Modifier.padding(start = 8.dp))
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
        SectionTitle(stringResource(R.string.admin_info))
        InfoRow(stringResource(R.string.admin_field_id), user.id.toString())
        InfoRow(stringResource(R.string.admin_field_hash_id), user.hashId)
        InfoRow(stringResource(R.string.admin_field_created), formatTime(user.createdAt))
        InfoRow(stringResource(R.string.admin_field_updated), formatTime(user.updatedAt))
        InfoRow(
            stringResource(R.string.admin_user_two_fa),
            stringResource(if (user.twoFaEnabled) R.string.admin_yes else R.string.admin_no),
        )

        if (user.id != 1) {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(Modifier.padding(16.dp)) {
                Button(
                    enabled = state.canDelete,
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.admin_user_delete), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
