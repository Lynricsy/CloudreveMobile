package plus.ling.opencloudreve.ui.share

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.di.ShareEditParams
import plus.ling.opencloudreve.core.presentation.share.ShareEditEffect
import plus.ling.opencloudreve.core.presentation.share.ShareEditIntent
import plus.ling.opencloudreve.core.presentation.share.ShareEditResult
import plus.ling.opencloudreve.core.presentation.share.ShareEditState
import plus.ling.opencloudreve.core.presentation.share.ShareEditViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.formatDateTime
import plus.ling.opencloudreve.ui.files.rememberCopyToClipboard
import plus.ling.opencloudreve.ui.settings.QrCode

/** 有效期预设（秒）：1 天、7 天、30 天 */
private val EXPIRE_CHOICES = listOf(86_400L, 604_800L, 2_592_000L)

/**
 * 新建（[fileUri]）或编辑（[shareId]）分享的对话框；保存成功后切换为链接结果（复制/二维码/系统分享）。
 * @param onSaved 保存成功后调用（分享列表据此刷新）
 */
@Composable
fun ShareEditDialog(
    accountId: String,
    fileUri: String? = null,
    shareId: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val vm: ShareEditViewModel = koinViewModel(key = "share-edit:${fileUri ?: shareId}") {
        parametersOf(ShareEditParams(accountId, fileUri, shareId))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ShareEditEffect.Saved -> onSaved()
            is ShareEditEffect.Failed -> error = effect.error.message(context)
        }
    }
    val result = state.result
    if (result != null) {
        ShareResultDialog(result, state.name, onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (state.isEditing) R.string.share_edit_title else R.string.share_create_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> Text(state.loadError!!.message())
                else -> ShareForm(state, error, vm::on)
            }
        },
        confirmButton = {
            TextButton(onClick = { error = null; vm.on(ShareEditIntent.Submit) }, enabled = state.canSubmit) {
                Text(stringResource(if (state.isEditing) R.string.action_save else R.string.share_create_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareForm(state: ShareEditState, error: String?, onIntent: (ShareEditIntent) -> Unit) {
    // 自定义有效期（天）输入；选中预设时为空
    var customDays by rememberSaveable { mutableStateOf("") }
    val presetSelected = state.expireSeconds in EXPIRE_CHOICES
    var custom by rememberSaveable { mutableStateOf(state.originalExpires == null && state.expireSeconds != null && !presetSelected) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)

        SwitchRow(stringResource(R.string.share_private), state.isPrivate) { onIntent(ShareEditIntent.SetPrivate(it)) }
        if (state.isPrivate) {
            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(ShareEditIntent.SetPassword(it)) },
                label = { Text(stringResource(R.string.share_password)) },
                placeholder = { Text(stringResource(R.string.share_password_auto)) },
                isError = !state.passwordValid,
                supportingText = if (!state.passwordValid) {
                    { Text(stringResource(R.string.share_password_invalid)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.showDownloadLimit) {
            SwitchRow(stringResource(R.string.share_limit_downloads), state.downloadLimit != null) { on ->
                onIntent(ShareEditIntent.SetDownloadLimit(if (on) 1 else null))
            }
            state.downloadLimit?.let { limit ->
                OutlinedTextField(
                    value = limit.toString(),
                    onValueChange = { text -> onIntent(ShareEditIntent.SetDownloadLimit(text.filter(Char::isDigit).take(6).toIntOrNull() ?: 0)) },
                    label = { Text(stringResource(R.string.share_download_times)) },
                    isError = !state.downloadLimitValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(stringResource(R.string.share_expire), style = MaterialTheme.typography.labelLarge)
        val original = state.originalExpires
        if (original != null) {
            Text(
                stringResource(R.string.share_expire_keep, formatDateTime(original)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.expireSeconds == null && !custom,
                onClick = { custom = false; onIntent(ShareEditIntent.SetExpire(null)) },
                label = { Text(stringResource(R.string.share_expire_never)) },
            )
            EXPIRE_CHOICES.forEach { seconds ->
                val days = (seconds / 86_400).toInt()
                FilterChip(
                    selected = !custom && original == null && state.expireSeconds == seconds,
                    onClick = { custom = false; onIntent(ShareEditIntent.SetExpire(seconds)) },
                    label = { Text(pluralStringResource(R.plurals.share_days, days, days)) },
                )
            }
            FilterChip(
                selected = custom,
                onClick = {
                    custom = true
                    customDays.toLongOrNull()?.takeIf { it > 0 }?.let { onIntent(ShareEditIntent.SetExpire(it * 86_400)) }
                },
                label = { Text(stringResource(R.string.share_expire_custom)) },
            )
        }
        if (custom) {
            OutlinedTextField(
                value = customDays,
                onValueChange = { text ->
                    customDays = text.filter(Char::isDigit).take(5)
                    customDays.toLongOrNull()?.takeIf { it > 0 }?.let { onIntent(ShareEditIntent.SetExpire(it * 86_400)) }
                },
                label = { Text(stringResource(R.string.share_expire_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.showFolderOptions) {
            SwitchRow(stringResource(R.string.share_view_option), state.shareView) { onIntent(ShareEditIntent.SetShareView(it)) }
            SwitchRow(stringResource(R.string.share_readme_option), state.showReadme) { onIntent(ShareEditIntent.SetShowReadme(it)) }
        }
        if (state.submitting) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 整行可点按切换（标签也是触控目标）
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 保存成功：显示链接、密码与二维码，可复制或系统分享 */
@Composable
private fun ShareResultDialog(result: ShareEditResult, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copy = rememberCopyToClipboard(null)
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_created_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(result.link, contentDescription = stringResource(R.string.share_qr_cd), size = 180.dp)
                Text(result.link, style = MaterialTheme.typography.bodyMedium)
                result.password?.let { Text(stringResource(R.string.share_password_value, it), style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { copy(result.link) } }) { Text(stringResource(R.string.action_copy_link)) }
                    TextButton(onClick = { shareLinkViaSystem(context, name, result.link) }) { Text(stringResource(R.string.share_system_share)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

/** 通过系统分享面板发送分享链接 */
internal fun shareLinkViaSystem(context: Context, name: String, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_system_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
