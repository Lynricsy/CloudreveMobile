package plus.ling.opencloudreve.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerTextEffect
import plus.ling.opencloudreve.core.presentation.viewer.ViewerTextIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerTextViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.LockConflictDialog

/**
 * 文本 / Markdown 预览与编辑（monaco、markdown）：等宽与自动换行切换、Markdown 预览；
 * 保存冲突 40076 询问“覆盖 / 另存为”，40073 锁冲突可强制解锁后重试，40049 提示超限。
 */
@Composable
internal fun TextViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerTextViewModel = koinViewModel(key = "text:${params.file.path}:${params.version}:${params.viewerId}") { parametersOf(params) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var monospace by rememberSaveable { mutableStateOf(!state.isMarkdown) }
    // Markdown 默认预览；只读文本也直接显示
    var preview by rememberSaveable { mutableStateOf(state.isMarkdown) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf<String?>(null) }
    // 放弃修改时递增，让编辑区按 VM 的内容重建
    var editorReset by remember { mutableIntStateOf(0) }
    val resources = LocalResources.current

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is ViewerTextEffect.Saved -> snackbar.showSnackbar(resources.getString(R.string.viewer_saved))
            is ViewerTextEffect.SavedAs -> snackbar.showSnackbar(resources.getString(R.string.viewer_saved_as, effect.file.name))
            is ViewerTextEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }

    val leave = { if (state.dirty) confirmLeave = true else onClose() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }

    ViewerScaffold(
        title = state.file.name,
        subtitle = when {
            state.readOnly -> stringResource(R.string.viewer_read_only)
            state.dirty -> stringResource(R.string.viewer_unsaved)
            else -> null
        },
        menu = menu,
        onClose = leave,
        snackbar = snackbar,
        actions = {
            if (state.isMarkdown) {
                IconButton(onClick = { preview = !preview }) {
                    Icon(
                        if (preview) Icons.Default.Edit else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (preview) R.string.viewer_edit else R.string.viewer_preview),
                    )
                }
            }
            if (!state.readOnly) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { vm.on(ViewerTextIntent.Save) }, enabled = state.canSave) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.viewer_save))
                    }
                }
            }
        },
        extraMenu = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_wrap)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = null) },
                trailingIcon = { if (wrap) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { dismiss(); wrap = !wrap },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_monospace)) },
                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                trailingIcon = { if (monospace) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { dismiss(); monospace = !monospace },
            )
            if (state.dirty) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_discard)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                    onClick = {
                        dismiss()
                        vm.on(ViewerTextIntent.Discard)
                        editorReset++
                    },
                )
            }
            HorizontalDivider()
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val err = state.error
            when {
                state.loading -> CenteredProgress()
                err != null -> CenteredError(err.message(), onRetry = { vm.on(ViewerTextIntent.Reload) })
                preview && state.isMarkdown -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Markdown(
                        content = state.draft,
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.headlineMedium,
                            h2 = MaterialTheme.typography.headlineSmall,
                            h3 = MaterialTheme.typography.titleLarge,
                            h4 = MaterialTheme.typography.titleMedium,
                            h5 = MaterialTheme.typography.titleSmall,
                            h6 = MaterialTheme.typography.labelLarge,
                        ),
                    )
                }
                else -> TextEditor(
                    resetKey = editorReset,
                    initial = { vm.state.value.draft },
                    readOnly = state.readOnly,
                    wrap = wrap,
                    monospace = monospace,
                    onChange = { vm.on(ViewerTextIntent.Edit(it)) },
                )
            }
        }
    }

    if (state.staleVersion) {
        AlertDialog(
            onDismissRequest = { vm.on(ViewerTextIntent.DismissConflict) },
            title = { Text(stringResource(R.string.viewer_stale_title)) },
            text = { Text(stringResource(R.string.viewer_stale_message)) },
            confirmButton = {
                TextButton(onClick = { vm.on(ViewerTextIntent.Overwrite) }) { Text(stringResource(R.string.viewer_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val name = state.file.name
                    val base = name.substringBeforeLast('.', name)
                    val ext = name.substringAfterLast('.', "")
                    saveAsName = if (ext.isEmpty()) "$base (1)" else "$base (1).$ext"
                }) { Text(stringResource(R.string.viewer_save_as)) }
            },
        )
    }
    saveAsName?.let { initial ->
        SaveAsDialog(
            initial = initial,
            onDismiss = { saveAsName = null },
            onConfirm = { name ->
                saveAsName = null
                val parent = state.file.uri()?.parent()?.toString()
                if (parent != null) vm.on(ViewerTextIntent.SaveAs(parent, name))
            },
        )
    }
    if (state.lockConflicts.isNotEmpty()) {
        LockConflictDialog(
            conflicts = state.lockConflicts,
            onForceUnlock = { vm.on(ViewerTextIntent.ForceUnlockAndSave) },
            onDismiss = { vm.on(ViewerTextIntent.DismissConflict) },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.viewer_leave_title)) },
            text = { Text(stringResource(R.string.viewer_leave_message)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onClose() }) { Text(stringResource(R.string.viewer_discard_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 编辑区：本地持有文本与光标，内容变化回传 VM。不随 VM 的 draft 回流刷新（快速输入时回流的是旧值，会把光标打回开头），
 * 只在 [resetKey] 变化（放弃修改）或重新进入组合（加载完成、从预览切回）时用 [initial] 重新取值。
 */
@Composable
private fun TextEditor(resetKey: Int, initial: () -> String, readOnly: Boolean, wrap: Boolean, monospace: Boolean, onChange: (String) -> Unit) {
    var value by remember(resetKey) { mutableStateOf(TextFieldValue(initial())) }
    val style = MaterialTheme.typography.bodyMedium.merge(
        TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val field = @Composable { modifier: Modifier ->
            BasicTextField(
                value = value,
                onValueChange = {
                    val changed = it.text != value.text
                    value = it
                    if (changed) onChange(it.text)
                },
                readOnly = readOnly,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = modifier.padding(16.dp),
            )
        }
        if (wrap) {
            field(Modifier.fillMaxSize())
        } else {
            // 不换行：横向可滚动，宽度至少铺满屏幕
            val minWidth = maxWidth
            val minHeight = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                field(Modifier.widthIn(min = minWidth).heightIn(min = minHeight))
            }
        }
    }
}

@Composable
private fun SaveAsDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_save_as)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.viewer_file_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.viewer_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
