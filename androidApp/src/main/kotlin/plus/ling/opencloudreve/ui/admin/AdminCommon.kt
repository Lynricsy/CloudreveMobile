package plus.ling.opencloudreve.ui.admin

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.ui.common.message
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant

/** 管理页 Scaffold 的内容 inset：底部由外层 NavigationSuiteScaffold 处理，避免双重 inset */
internal val adminContentInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** 字节数格式化（1024 进制，保留一位小数） */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, BYTE_UNITS[unit])
}

private val BYTE_UNITS = arrayOf("KB", "MB", "GB", "TB", "PB")

/** 本地时区、本地格式的日期时间；空值显示“—” */
internal fun formatTime(instant: Instant?): String {
    if (instant == null) return EMPTY_VALUE
    return java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
}

/** 纳秒时长格式化：不足 1 分钟显示秒（一位小数），否则 h:mm:ss */
internal fun formatDurationNanos(nanos: Long): String {
    val totalSeconds = nanos / 1_000_000_000L
    if (totalSeconds < 60) return String.format(Locale.getDefault(), "%.1f s", nanos / 1_000_000_000.0)
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

internal const val EMPTY_VALUE = "—"

/** 管理端操作失败文案：交接版本过低（FeatureDisabled + 服务端版本）显示升级提示 */
internal fun adminErrorText(context: Context, error: UiError): String {
    val version = error.serverMsg
    return if (error.kind == ErrorKind.FeatureDisabled && !version.isNullOrBlank()) {
        context.getString(R.string.handoff_unsupported, version)
    } else {
        error.message(context)
    }
}

/**
 * TopAppBar 上的“在网页后台打开”：交接进行中显示小进度。已知不支持且知道版本时直接提示；
 * 版本未知时仍交给 VM 尝试，VM 失败事件会带上服务端版本（[adminErrorText] 显示升级提示）。
 */
@Composable
internal fun OpenInWebAction(
    handoff: HandoffAvailability,
    opening: Boolean,
    serverVersion: String?,
    snackbar: SnackbarHostState,
    onOpen: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val version = serverVersion?.takeIf { it.isNotBlank() }
    val unsupportedText = version?.let { stringResource(R.string.handoff_unsupported, it) }
    IconButton(
        enabled = !opening,
        onClick = {
            if (handoff == HandoffAvailability.Unsupported && unsupportedText != null) {
                scope.launch { snackbar.showSnackbar(unsupportedText) }
            } else {
                onOpen()
            }
        },
    ) {
        if (opening) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.admin_open_in_web))
        }
    }
}

/** 复制文本到剪贴板并提示 */
@Composable
internal fun rememberAdminCopy(snackbar: SnackbarHostState): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val copied = stringResource(R.string.admin_copied)
    val scope = rememberCoroutineScope()
    return remember(clipboard, snackbar, scope, copied) {
        { text: String ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text)))
                snackbar.showSnackbar(copied)
            }
        }
    }
}

/** 状态标签色调 */
internal enum class ChipTone { Positive, Negative, Warning, Neutral }

/** 只读状态标签 */
@Composable
internal fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.Positive -> colors.primaryContainer to colors.onPrimaryContainer
        ChipTone.Negative -> colors.errorContainer to colors.onErrorContainer
        ChipTone.Warning -> colors.tertiaryContainer to colors.onTertiaryContainer
        ChipTone.Neutral -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** 列表行（透明背景，由列表脚手架绘制选中态） */
@Composable
internal fun AdminListItem(
    headline: String,
    supporting: String? = null,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        overlineContent = overline?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing,
    )
}

/** 详情分节标题 */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 详情字段：标签 + 可选中的值，可带复制按钮 */
@Composable
internal fun InfoRow(label: String, value: String, onCopy: ((String) -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value.ifEmpty { EMPTY_VALUE }, style = MaterialTheme.typography.bodyMedium) }
        }
        if (onCopy != null && value.isNotEmpty()) {
            IconButton(onClick = { onCopy(value) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.admin_copy))
            }
        }
    }
}

/** 可折叠的 JSON 原文（默认折叠；展开后可选中、等宽字体） */
@Composable
internal fun CollapsibleJson(title: String, json: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { expanded = !expanded },
        headlineContent = { Text(title) },
        trailingContent = {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.admin_collapse else R.string.admin_expand),
            )
        },
    )
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    json,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

/** 尝试把 JSON 字符串格式化；解析失败返回原文 */
internal fun prettyJson(raw: String): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }.getOrDefault(raw)

internal fun prettyJson(element: JsonElement): String = prettyJson.encodeToString(JsonElement.serializer(), element)

private val SECRET_KEY_PARTS = listOf("password", "token", "secret", "key")
private val REDACTED = JsonPrimitive("••••")

/** 脱敏：键名包含 password/token/secret/key 的值替换为 `••••`（递归） */
internal fun redactSecrets(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (k, v) ->
            val lower = k.lowercase(Locale.ROOT)
            if (SECRET_KEY_PARTS.any { it in lower }) REDACTED else redactSecrets(v)
        },
    )
    is JsonArray -> JsonArray(element.map(::redactSecrets))
    else -> element
}

/** 文本/数字筛选：点按弹出输入对话框 */
@Composable
internal fun TextFilterChip(label: String, value: String?, numeric: Boolean = false, onSet: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    FilterChip(
        selected = value != null,
        onClick = { editing = true },
        label = { Text(if (value != null) "$label: $value" else label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (value != null) {
            { Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
    if (editing) {
        var text by remember { mutableStateOf(value.orEmpty()) }
        val valid = !numeric || text.isEmpty() || text.all(Char::isDigit)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    editing = false
                    onSet(text.trim().ifEmpty { null })
                }) { Text(stringResource(R.string.admin_filter_apply)) }
            },
            dismissButton = {
                Row {
                    if (value != null) {
                        TextButton(onClick = {
                            editing = false
                            onSet(null)
                        }) { Text(stringResource(R.string.admin_filter_clear_value)) }
                    }
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/** 单选筛选：点按弹出菜单（含“全部”） */
@Composable
internal fun OptionFilterChip(label: String, value: String?, options: List<Pair<String, String>>, onSet: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = value?.let { v -> options.firstOrNull { it.first == v }?.second ?: v }
    Box {
        FilterChip(
            selected = value != null,
            onClick = { open = true },
            label = { Text(if (current != null) "$label: $current" else label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.admin_filter_all)) },
                leadingIcon = { CheckSlot(value == null) },
                onClick = {
                    open = false
                    onSet(null)
                },
            )
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { CheckSlot(value == key) },
                    onClick = {
                        open = false
                        onSet(key)
                    },
                )
            }
        }
    }
}

/** 布尔筛选（选中时条件值为 `true`） */
@Composable
internal fun ToggleFilterChip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/** 菜单项前的勾选占位（保持文字对齐） */
@Composable
internal fun CheckSlot(checked: Boolean) {
    if (checked) Icon(Icons.Outlined.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
}

/** 只读下拉选择字段（表单用） */
@Composable
internal fun <K> DropdownField(
    label: String,
    selected: K,
    options: List<Pair<K, String>>,
    enabled: Boolean,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == selected }?.second ?: selected?.toString().orEmpty()
    Box(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // 只读输入框会吞掉点按，用覆盖层接收
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, optionText) ->
                DropdownMenuItem(
                    text = { Text(optionText) },
                    leadingIcon = { CheckSlot(key == selected) },
                    onClick = {
                        open = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/** 横向排列的筛选行 */
@Composable
internal fun FilterRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
