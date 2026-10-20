package plus.ling.opencloudreve.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import kotlinx.coroutines.flow.Flow
import plus.ling.opencloudreve.BuildConfig
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.OAuthGrant
import plus.ling.opencloudreve.core.model.Passkey
import plus.ling.opencloudreve.core.presentation.settings.SecurityEffect
import plus.ling.opencloudreve.core.presentation.settings.SecurityIntent
import plus.ling.opencloudreve.core.presentation.settings.SecurityOperation
import plus.ling.opencloudreve.core.presentation.settings.SecurityState
import plus.ling.opencloudreve.core.presentation.settings.TwoFactorSetup
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.formatDateTime

/**
 * 安全设置：修改密码、两步验证（二维码 + 密钥）、Passkey（列表/删除/在本机添加）、已授权应用。
 * 改密码成功后账户已在本地登出，[onReLogin] 引导到该站点的登录页。
 */
@Composable
fun SecurityScreen(
    state: SecurityState,
    effects: Flow<SecurityEffect>,
    onIntent: (SecurityIntent) -> Unit,
    onBack: () -> Unit,
    onReLogin: (siteUrl: String, email: String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingPasskey by remember { mutableStateOf<Passkey?>(null) }
    var revokingGrant by remember { mutableStateOf<OAuthGrant?>(null) }

    CollectEffects(effects) { effect ->
        when (effect) {
            is SecurityEffect.PasswordChanged -> onReLogin(effect.siteUrl, effect.email)
            SecurityEffect.TwoFactorEnabled -> scope.snack(snackbar, resources.getString(R.string.security_2fa_enabled))
            SecurityEffect.TwoFactorDisabled -> {
                dialog = null
                scope.snack(snackbar, resources.getString(R.string.security_2fa_disabled))
            }
            is SecurityEffect.CreatePasskey -> onIntent(createPasskey(context, effect.requestJson))
            is SecurityEffect.PasskeyAdded -> scope.snack(snackbar, resources.getString(R.string.security_passkey_added))
            SecurityEffect.PasskeyDeleted -> scope.snack(snackbar, resources.getString(R.string.security_passkey_deleted))
            SecurityEffect.GrantRevoked -> scope.snack(snackbar, resources.getString(R.string.security_grant_revoked))
            is SecurityEffect.Failed -> scope.snack(snackbar, effect.error.message(context))
        }
    }

    SettingsScaffold(title = stringResource(R.string.security_title), onBack = onBack, snackbar = snackbar) { padding ->
        RefreshableColumn(
            refreshing = state.loading && state.settings != null,
            onRefresh = { onIntent(SecurityIntent.Refresh) },
            padding = padding,
        ) {
            state.error?.takeIf { state.settings == null }?.let { LoadError(it) { onIntent(SecurityIntent.Refresh) } }
            val ready = state.settings != null

            SectionHeader(stringResource(R.string.security_section_login))
            SettingRow(
                title = stringResource(R.string.security_change_password),
                subtitle = if (ready && !state.canChangePassword) stringResource(R.string.security_passwordless) else null,
                icon = Icons.Outlined.Password,
                enabled = state.canChangePassword,
                busy = state.busy == SecurityOperation.ChangePassword,
                onClick = { dialog = "password" },
            )
            SwitchRow(
                title = stringResource(R.string.security_2fa),
                subtitle = stringResource(
                    if (state.twoFactorEnabled) R.string.security_2fa_on_desc else R.string.security_2fa_off_desc,
                ),
                icon = Icons.Outlined.PhonelinkLock,
                checked = state.twoFactorEnabled,
                enabled = ready,
                busy = state.busy == SecurityOperation.EnableTwoFactor || state.busy == SecurityOperation.DisableTwoFactor,
                onCheckedChange = { on ->
                    if (on) onIntent(SecurityIntent.BeginEnableTwoFactor) else dialog = "disable2fa"
                },
            )

            SectionHeader(stringResource(R.string.security_section_passkeys))
            if (ready && state.passkeys.isEmpty()) {
                Text(
                    stringResource(R.string.security_passkeys_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.passkeys.forEach { passkey ->
                SettingRow(
                    title = passkey.name.ifBlank { passkey.id },
                    subtitle = passkeySubtitle(passkey),
                    icon = Icons.Outlined.Key,
                    trailing = {
                        IconButton(onClick = { deletingPasskey = passkey }, enabled = state.busy == null) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.security_passkey_delete))
                        }
                    },
                )
            }
            if (state.passkeyRegistrationSupported) {
                SettingRow(
                    title = stringResource(R.string.security_passkey_add),
                    icon = Icons.Outlined.Add,
                    enabled = ready && !state.passkeyRegistrationPending,
                    busy = state.busy == SecurityOperation.RegisterPasskey || state.passkeyRegistrationPending,
                    onClick = { onIntent(SecurityIntent.BeginPasskeyRegistration) },
                )
            }

            SectionHeader(stringResource(R.string.security_section_apps))
            if (ready && state.oauthGrants.isEmpty()) {
                Text(
                    stringResource(R.string.security_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.oauthGrants.forEach { grant ->
                SettingRow(
                    title = grant.clientName.ifBlank { grant.clientId },
                    subtitle = grantSubtitle(grant),
                    icon = Icons.Outlined.Apps,
                    trailing = {
                        TextButton(onClick = { revokingGrant = grant }, enabled = state.busy == null) {
                            Text(stringResource(R.string.security_grant_revoke))
                        }
                    },
                )
            }
        }
    }

    when (dialog) {
        "password" -> ChangePasswordDialog(
            onConfirm = { current, new ->
                dialog = null
                onIntent(SecurityIntent.ChangePassword(current, new))
            },
            onDismiss = { dialog = null },
        )
        "disable2fa" -> OtpDialog(
            title = stringResource(R.string.security_2fa_disable_title),
            message = stringResource(R.string.security_2fa_disable_message),
            busy = state.busy == SecurityOperation.DisableTwoFactor,
            confirm = stringResource(R.string.security_2fa_disable),
            onConfirm = { onIntent(SecurityIntent.DisableTwoFactor(it)) },
            onDismiss = { dialog = null },
        )
    }
    state.twoFactorSetup?.let { setup ->
        EnableTwoFactorDialog(
            setup = setup,
            busy = state.busy == SecurityOperation.EnableTwoFactor,
            onConfirm = { onIntent(SecurityIntent.ConfirmEnableTwoFactor(it)) },
            onDismiss = { onIntent(SecurityIntent.CancelEnableTwoFactor) },
        )
    }
    deletingPasskey?.let { passkey ->
        ConfirmDialog(
            title = stringResource(R.string.security_passkey_delete),
            message = stringResource(R.string.security_passkey_delete_message, passkey.name.ifBlank { passkey.id }),
            confirm = stringResource(R.string.settings_delete),
            onConfirm = {
                deletingPasskey = null
                onIntent(SecurityIntent.DeletePasskey(passkey.id))
            },
            onDismiss = { deletingPasskey = null },
        )
    }
    revokingGrant?.let { grant ->
        ConfirmDialog(
            title = stringResource(R.string.security_grant_revoke),
            message = stringResource(R.string.security_grant_revoke_message, grant.clientName.ifBlank { grant.clientId }),
            confirm = stringResource(R.string.security_grant_revoke),
            onConfirm = {
                revokingGrant = null
                onIntent(SecurityIntent.RevokeOAuthGrant(grant.clientId))
            },
            onDismiss = { revokingGrant = null },
        )
    }
}

/** 调起 Credential Manager 创建 Passkey，结果转为完成/取消意图（`registrationResponseJson` 原样提交） */
private suspend fun createPasskey(context: Context, requestJson: String): SecurityIntent = try {
    val response = CredentialManager.create(context).createCredential(context, CreatePublicKeyCredentialRequest(requestJson))
    if (response is CreatePublicKeyCredentialResponse) {
        SecurityIntent.FinishPasskeyRegistration(
            responseJson = response.registrationResponseJson,
            name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            userAgent = "OpenCloudreve/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})",
        )
    } else {
        SecurityIntent.CancelPasskeyRegistration
    }
} catch (_: CreateCredentialCancellationException) {
    SecurityIntent.CancelPasskeyRegistration
} catch (e: CreateCredentialException) {
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.security_passkey_failed, e.errorMessage?.toString() ?: e.type),
        android.widget.Toast.LENGTH_LONG,
    ).show()
    SecurityIntent.CancelPasskeyRegistration
}

@Composable
private fun passkeySubtitle(passkey: Passkey): String {
    val created = passkey.createdAt?.let { stringResource(R.string.security_passkey_created, formatDateTime(it)) }
    val used = passkey.usedAt?.let { stringResource(R.string.security_passkey_used, formatDateTime(it)) }
    return listOfNotNull(created, used).joinToString(" · ")
}

@Composable
private fun grantSubtitle(grant: OAuthGrant): String {
    val scopes = stringResource(R.string.security_grant_scopes, grant.scopes.size)
    val used = grant.lastUsedAt?.let { stringResource(R.string.security_passkey_used, formatDateTime(it)) }
    return listOfNotNull(scopes, used).joinToString(" · ")
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    val mismatch = repeat.isNotEmpty() && repeat != new
    val valid = current.length in 4..128 && new.length in 6..128 && new == repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.security_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.security_password_relogin), style = MaterialTheme.typography.bodyMedium)
                PasswordField(current, { current = it }, stringResource(R.string.security_password_current))
                PasswordField(
                    new,
                    { new = it },
                    stringResource(R.string.security_password_new),
                    supporting = stringResource(R.string.register_password_hint),
                )
                PasswordField(
                    repeat,
                    { repeat = it },
                    stringResource(R.string.login_password_confirm),
                    supporting = if (mismatch) stringResource(R.string.register_password_mismatch) else null,
                    isError = mismatch,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current, new) }, enabled = valid) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(128)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 6 位验证码输入框；键盘“完成”在输满 6 位时直接提交（对话框按钮可能被键盘遮住） */
@Composable
private fun OtpInput(value: String, onChange: (String) -> Unit, onSubmit: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        label = { Text(stringResource(R.string.twofa_input)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.length == 6) onSubmit(value) }),
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OtpDialog(
    title: String,
    message: String,
    busy: Boolean,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OtpInput(code, { code = it }, onSubmit = { if (!busy) onConfirm(it) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.length == 6 && !busy) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EnableTwoFactorDialog(
    setup: TwoFactorSetup,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var code by rememberSaveable(setup.secret) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.security_2fa_enable_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.security_2fa_enable_message), style = MaterialTheme.typography.bodyMedium)
                QrCode(setup.otpauthUrl, contentDescription = stringResource(R.string.security_2fa_qr_desc), size = 180.dp)
                Text(stringResource(R.string.security_2fa_secret), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(setup.secret, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = {
                    copyToClipboard(context, resources.getString(R.string.security_2fa_secret), setup.secret, sensitive = true)
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.settings_copy), modifier = Modifier.padding(start = 8.dp))
                }
                OtpInput(code, { code = it }, onSubmit = { if (!busy) onConfirm(it) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.length == 6 && !busy) {
                Text(stringResource(R.string.security_2fa_enable))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
