package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.CustomPropDefinition
import plus.ling.opencloudreve.core.presentation.files.CustomPropCodec
import plus.ling.opencloudreve.core.presentation.files.CustomPropEntry
import plus.ling.opencloudreve.core.presentation.files.FileTag
import plus.ling.opencloudreve.core.presentation.files.MetadataEditEffect
import plus.ling.opencloudreve.core.presentation.files.MetadataEditIntent
import plus.ling.opencloudreve.core.presentation.files.MetadataEditState
import plus.ling.opencloudreve.core.presentation.files.MetadataEditViewModel
import plus.ling.opencloudreve.core.presentation.files.PropValidationError
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message

/**
 * 标签与元数据：标签（草稿，保存时提交差异）、图标（emoji 预设、目录颜色，立即生效）、自定义属性（单个文件，按类型编辑，立即生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEditScreen(accountId: String, uris: List<String>, onClose: () -> Unit) {
    val vm: MetadataEditViewModel = koinViewModel(key = "metadata:${uris.joinToString("|")}") { parametersOf(accountId, uris) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            MetadataEditEffect.Updated -> snackbar.showSnackbar(resources.getString(R.string.info_updated))
            is MetadataEditEffect.Failed -> snackbar.showSnackbar(effect.error.message(context))
        }
    }
    val title = state.files.singleOrNull()?.name ?: uris.singleOrNull()?.let { CrUri.parseOrNull(it)?.name() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.action_metadata))
                        Text(
                            title ?: stringResource(R.string.selection_count, uris.size),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = state.error
            when {
                state.loading && state.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && state.files.isEmpty() -> FullError(error) { vm.on(MetadataEditIntent.Refresh) }
                else -> MetadataContent(state, vm::on)
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataContent(state: MetadataEditState, onIntent: (MetadataEditIntent) -> Unit) {
    var addingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<FileTag?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        if (!state.canEdit) {
            Text(
                stringResource(R.string.metadata_readonly),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        // 标签
        SectionLabel(stringResource(R.string.info_tags))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { if (state.canEdit) editingTag = tag },
                    label = { Text(tag.name) },
                    leadingIcon = parseHexColor(tag.color)?.let { c -> { Box(Modifier.size(10.dp).background(c, CircleShape)) } },
                    trailingIcon = if (state.canEdit) {
                        {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.metadata_remove_tag, tag.name),
                                modifier = Modifier.size(18.dp).clickable { onIntent(MetadataEditIntent.RemoveTag(tag.name)) },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            if (state.canEdit) {
                AssistChip(
                    onClick = { addingTag = true },
                    label = { Text(stringResource(R.string.metadata_add_tag)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        if (state.tags.isEmpty() && !state.canEdit) {
            Text(stringResource(R.string.metadata_no_tags), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.tagsDirty) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onIntent(MetadataEditIntent.SaveTags) }, enabled = !state.saving) { Text(stringResource(R.string.metadata_save_tags)) }
                TextButton(onClick = { onIntent(MetadataEditIntent.ResetTags) }, enabled = !state.saving) { Text(stringResource(R.string.metadata_reset_tags)) }
            }
        }

        // 图标
        if (state.canEdit) {
            HorizontalDivider(Modifier.padding(top = 12.dp))
            SectionLabel(stringResource(R.string.metadata_icon))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.emoji ?: stringResource(R.string.metadata_icon_default),
                    style = if (state.emoji != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.emoji != null) {
                    TextButton(onClick = { onIntent(MetadataEditIntent.SetEmoji(null)) }) { Text(stringResource(R.string.metadata_icon_reset)) }
                }
            }
            if (state.emojiPresets.isNotEmpty()) EmojiPicker(state.emojiPresets, state.emoji) { onIntent(MetadataEditIntent.SetEmoji(it)) }
            if (state.canChangeIconColor) {
                SectionLabel(stringResource(R.string.metadata_icon_color))
                ColorPalette(state.presetColors, state.iconColor, allowNone = true) { onIntent(MetadataEditIntent.SetIconColor(it)) }
            }
        }

        // 自定义属性
        if (state.isSingle && (state.props.isNotEmpty() || state.canEditProps && state.customPropDefinitions.isNotEmpty())) {
            HorizontalDivider(Modifier.padding(top = 12.dp))
            SectionLabel(stringResource(R.string.metadata_props))
            state.props.forEach { entry ->
                PropEditor(entry, state, onIntent)
            }
            if (state.canEditProps && state.availableProps.isNotEmpty()) {
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.metadata_add_prop))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        state.availableProps.forEach { def ->
                            DropdownMenuItem(text = { Text(siteText(LocalContext.current, def.name)) }, onClick = { menu = false; onIntent(MetadataEditIntent.AddProp(def.id)) })
                        }
                    }
                }
            }
        }
    }

    if (addingTag) {
        TagDialog(initial = null, colors = state.presetColors, onDismiss = { addingTag = false }) { name, color ->
            addingTag = false
            onIntent(MetadataEditIntent.AddTag(name, color))
        }
    }
    editingTag?.let { tag ->
        TagDialog(initial = tag, colors = state.presetColors, onDismiss = { editingTag = null }) { _, color ->
            editingTag = null
            onIntent(MetadataEditIntent.SetTagColor(tag.name, color))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiPicker(presets: Map<String, List<String>>, current: String?, onPick: (String) -> Unit) {
    // 预设通常有上百个表情：默认折叠，避免把下方的属性挤出屏幕
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(if (expanded) R.string.metadata_emoji_hide else R.string.metadata_emoji_pick))
    }
    if (!expanded) return
    var category by rememberSaveable { mutableStateOf(presets.keys.first()) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        presets.keys.forEach { key ->
            FilterChip(selected = key == category, onClick = { category = key }, label = { Text(key) })
        }
    }
    FlowRow(Modifier.padding(horizontal = 12.dp)) {
        presets[category].orEmpty().forEach { emoji ->
            Box(
                Modifier
                    .size(44.dp)
                    .background(if (emoji == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape)
                    .clickable { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 24.sp) }
        }
    }
}

/** 预设颜色调色板；[allowNone] 时首项为“无” */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPalette(colors: List<String>, selected: String?, allowNone: Boolean, onPick: (String?) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 12.dp)) {
        if (allowNone) {
            Box(
                Modifier.padding(4.dp).size(32.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { onPick(null) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected == null) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.metadata_color_none), modifier = Modifier.size(18.dp))
            }
        }
        colors.forEach { hex ->
            val color = parseHexColor(hex) ?: return@forEach
            Box(
                Modifier.padding(4.dp).size(32.dp).background(color, CircleShape).clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected.equals(hex, ignoreCase = true)) Icon(Icons.Filled.Check, contentDescription = hex, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TagDialog(initial: FileTag?, colors: List<String>, onDismiss: () -> Unit, onConfirm: (String, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(initial?.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.metadata_add_tag else R.string.metadata_tag_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.metadata_tag_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ColorPalette(colors, color, allowNone = true) { color = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 单个自定义属性的编辑器（按类型） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropEditor(entry: CustomPropEntry, state: MetadataEditState, onIntent: (MetadataEditIntent) -> Unit) {
    val editable = state.canEditProps && entry.definition != null
    val def = entry.definition
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(siteText(context, entry.name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (state.canEditProps) {
                IconButton(onClick = { onIntent(MetadataEditIntent.RemoveProp(entry.id)) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.metadata_remove_prop))
                }
            }
        }
        when (entry.type) {
            CustomPropDefinition.TYPE_BOOLEAN -> Switch(
                checked = entry.booleanValue,
                onCheckedChange = { onIntent(MetadataEditIntent.SetProp(entry.id, CustomPropCodec.encodeBoolean(it))) },
                enabled = editable,
            )
            CustomPropDefinition.TYPE_RATING -> {
                val max = def?.max?.takeIf { it > 0 } ?: 5
                Row {
                    (1..max).forEach { star ->
                        IconButton(
                            onClick = { onIntent(MetadataEditIntent.SetProp(entry.id, CustomPropCodec.encodeRating(if (star == entry.ratingValue) null else star))) },
                            enabled = editable,
                        ) {
                            Icon(
                                if (star <= entry.ratingValue) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.metadata_rating, star),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            CustomPropDefinition.TYPE_SELECT -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                def?.options.orEmpty().forEach { option ->
                    FilterChip(
                        selected = entry.value == option,
                        onClick = { onIntent(MetadataEditIntent.SetProp(entry.id, if (entry.value == option) "" else option)) },
                        label = { Text(option) },
                        enabled = editable,
                    )
                }
            }
            CustomPropDefinition.TYPE_MULTI_SELECT -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val values = entry.multiSelectValues
                def?.options.orEmpty().forEach { option ->
                    FilterChip(
                        selected = option in values,
                        onClick = {
                            val next = if (option in values) values - option else values + option
                            onIntent(MetadataEditIntent.SetProp(entry.id, CustomPropCodec.encodeMultiSelect(next)))
                        },
                        label = { Text(option) },
                        enabled = editable,
                    )
                }
            }
            CustomPropDefinition.TYPE_USER -> UserPropEditor(entry, state, editable, onIntent)
            else -> TextPropEditor(entry, editable, numeric = entry.type == CustomPropDefinition.TYPE_NUMBER, onIntent)
        }
        state.propErrors[entry.id]?.let { err ->
            Text(propErrorText(err, def), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 文本/链接/数字：本地编辑，点勾提交 */
@Composable
private fun TextPropEditor(entry: CustomPropEntry, editable: Boolean, numeric: Boolean, onIntent: (MetadataEditIntent) -> Unit) {
    var text by remember(entry.value) { mutableStateOf(entry.value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = if (numeric) it.filter { c -> c.isDigit() || c == '-' } else it },
        enabled = editable,
        singleLine = !(entry.type == CustomPropDefinition.TYPE_TEXT && text.length > 40),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        trailingIcon = if (editable && text != entry.value) {
            {
                IconButton(onClick = { onIntent(MetadataEditIntent.SetProp(entry.id, text)) }) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_save))
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UserPropEditor(entry: CustomPropEntry, state: MetadataEditState, editable: Boolean, onIntent: (MetadataEditIntent) -> Unit) {
    val current = state.resolvedUsers[entry.value]
    var keyword by remember { mutableStateOf("") }
    Text(
        current?.nickname?.ifEmpty { null } ?: entry.value.ifEmpty { stringResource(R.string.metadata_user_none) },
        style = MaterialTheme.typography.bodyLarge,
    )
    if (editable) {
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                if (it.length >= 2) onIntent(MetadataEditIntent.SearchUsers(it))
            },
            label = { Text(stringResource(R.string.metadata_user_search)) },
            singleLine = true,
            trailingIcon = if (state.searchingUsers) {
                { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (keyword.length >= 2) {
            state.userSearchResults.forEach { user ->
                Text(
                    listOfNotNull(user.nickname.ifEmpty { null }, user.email).joinToString(" · "),
                    modifier = Modifier.fillMaxWidth().clickable {
                        keyword = ""
                        onIntent(MetadataEditIntent.SetProp(entry.id, user.id))
                    }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun propErrorText(error: PropValidationError, def: CustomPropDefinition?): String = when (error) {
    PropValidationError.TOO_SHORT -> stringResource(R.string.metadata_err_too_short, def?.min ?: 0)
    PropValidationError.TOO_LONG -> stringResource(R.string.metadata_err_too_long, def?.max ?: 0)
    PropValidationError.NOT_A_NUMBER -> stringResource(R.string.metadata_err_nan)
    PropValidationError.TOO_SMALL -> stringResource(R.string.metadata_err_too_small, def?.min ?: 0)
    PropValidationError.TOO_LARGE -> stringResource(R.string.metadata_err_too_large, def?.max ?: 0)
    PropValidationError.INVALID_OPTION -> stringResource(R.string.metadata_err_option)
    PropValidationError.NOT_BOOLEAN -> stringResource(R.string.metadata_err_boolean)
    PropValidationError.UNKNOWN_PROP -> stringResource(R.string.metadata_err_unknown)
}
