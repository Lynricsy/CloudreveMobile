package plus.ling.opencloudreve.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.settings.SettingsEffect
import plus.ling.opencloudreve.core.presentation.settings.SettingsField
import plus.ling.opencloudreve.core.presentation.settings.SettingsIntent
import plus.ling.opencloudreve.core.presentation.settings.SettingsState
import plus.ling.opencloudreve.core.presentation.settings.ShareLinksInProfile
import plus.ling.opencloudreve.core.presentation.settings.ThemeColorOption
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.theme.ThemePreferences
import plus.ling.opencloudreve.ui.theme.ThemeSettings
import plus.ling.opencloudreve.ui.theme.ThemeSource

/** 设置页导航目标 */
data class SettingsDestinations(
    val onBack: () -> Unit,
    val onSecurity: () -> Unit,
    val onWebDav: () -> Unit,
    val onPolicies: () -> Unit,
    val onAbout: () -> Unit,
)

/** 主题对话框里“跟随系统动态色”的占位键 */
private const val DYNAMIC_KEY = ""

/**
 * 设置页：外观（语言、主题色）、文件管理（视图同步、版本保留）、安全与隐私、WebDAV、存储策略、关于。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    effects: Flow<SettingsEffect>,
    onIntent: (SettingsIntent) -> Unit,
    theme: ThemeSettings,
    themePrefs: ThemePreferences,
    nav: SettingsDestinations,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    CollectEffects(effects) { effect ->
        when (effect) {
            is SettingsEffect.Saved -> if (effect.field == SettingsField.VersionRetention) {
                scope.snack(snackbar, resources.getString(R.string.settings_saved))
            }
            is SettingsEffect.Failed -> scope.snack(snackbar, effect.error.message(context))
        }
    }

    // 站点主题模式下，本机配色跟随服务端记录的主题（换设备/网页端修改后保持一致）
    LaunchedEffect(theme.source, state.selectedTheme, state.themeOptions) {
        if (theme.source != ThemeSource.Site) return@LaunchedEffect
        state.themeOptions.firstOrNull { it.key == state.selectedTheme }?.let {
            themePrefs.useSite(it.lightPrimary, it.darkPrimary)
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = nav.onBack, snackbar = snackbar) { padding ->
        RefreshableColumn(
            refreshing = state.loading && state.user != null,
            onRefresh = { onIntent(SettingsIntent.Refresh) },
            padding = padding,
        ) {
            state.error?.takeIf { state.user == null }?.let { LoadError(it) { onIntent(SettingsIntent.Refresh) } }

            SectionHeader(stringResource(R.string.settings_section_appearance))
            val appLanguage = remember(state.language) { AppLanguage.current(context) }
            SettingRow(
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(if (AppLanguage.supported) appLanguage else state.language),
                icon = Icons.Outlined.Language,
                busy = state.saving == SettingsField.Language,
                onClick = { dialog = "language" },
            )
            SettingRow(
                title = stringResource(R.string.settings_theme),
                subtitle = if (theme.source == ThemeSource.Dynamic) {
                    stringResource(R.string.settings_theme_dynamic)
                } else {
                    stringResource(R.string.settings_theme_site_named, state.selectedTheme.orEmpty())
                },
                icon = Icons.Outlined.Palette,
                busy = state.saving == SettingsField.Theme,
                onClick = { dialog = "theme" },
                trailing = if (theme.source == ThemeSource.Site) {
                    { state.themeOptions.firstOrNull { it.key == state.selectedTheme }?.let { ThemeSwatch(it) } }
                } else {
                    null
                },
            )

            SectionHeader(stringResource(R.string.settings_section_files))
            SwitchRow(
                title = stringResource(R.string.settings_view_sync),
                subtitle = stringResource(R.string.settings_view_sync_desc),
                icon = Icons.Outlined.Sync,
                checked = !state.disableViewSync,
                enabled = state.settings != null,
                busy = state.saving == SettingsField.ViewSync,
                onCheckedChange = { onIntent(SettingsIntent.SetDisableViewSync(!it)) },
            )
            SettingRow(
                title = stringResource(R.string.settings_version_retention),
                subtitle = when {
                    !state.versionRetentionEnabled -> stringResource(R.string.settings_off)
                    else -> versionRetentionSummary(state)
                },
                icon = Icons.Outlined.History,
                enabled = state.settings != null,
                busy = state.saving == SettingsField.VersionRetention,
                onClick = { dialog = "retention" },
            )

            SectionHeader(stringResource(R.string.settings_section_security))
            SettingRow(
                title = stringResource(R.string.security_title),
                subtitle = stringResource(R.string.security_desc),
                icon = Icons.Outlined.Security,
                onClick = nav.onSecurity,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            SettingRow(
                title = stringResource(R.string.settings_profile_shares),
                subtitle = profileSharesLabel(state.shareLinksInProfile),
                icon = Icons.Outlined.Visibility,
                enabled = state.settings != null,
                busy = state.saving == SettingsField.ShareLinksInProfile,
                onClick = { dialog = "profile" },
            )

            SectionHeader(stringResource(R.string.settings_section_storage))
            SettingRow(
                title = stringResource(R.string.dav_title),
                subtitle = stringResource(R.string.dav_desc),
                icon = Icons.Outlined.Dns,
                onClick = nav.onWebDav,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            SettingRow(
                title = stringResource(R.string.policies_title),
                subtitle = stringResource(R.string.policies_desc),
                icon = Icons.Outlined.Storage,
                onClick = nav.onPolicies,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )

            HorizontalDivider(Modifier.fillMaxWidth())
            SettingRow(
                title = stringResource(R.string.about_title),
                icon = Icons.Outlined.Info,
                onClick = nav.onAbout,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }

    when (dialog) {
        "language" -> {
            val current = if (AppLanguage.supported) AppLanguage.current(context) else state.language
            ChoiceDialog(
                title = stringResource(R.string.settings_language),
                options = listOf<String?>(null, "zh-CN", "en-US").map { it to languageLabel(it) },
                selected = current,
                onSelect = { lang ->
                    dialog = null
                    // 服务端记录具体语言（邮件等使用）；“跟随系统”时记录系统语言
                    onIntent(SettingsIntent.SetLanguage(lang ?: systemServerLanguage()))
                    AppLanguage.apply(context, lang)
                },
                onDismiss = { dialog = null },
            )
        }
        "theme" -> {
            val selected = if (theme.source == ThemeSource.Dynamic) DYNAMIC_KEY else state.selectedTheme
            val dynamicLabel = stringResource(R.string.settings_theme_dynamic)
            ChoiceDialog(
                title = stringResource(R.string.settings_theme),
                options = listOf(DYNAMIC_KEY to dynamicLabel) +
                    state.themeOptions.map { it.key to stringResource(R.string.settings_theme_site_named, it.key) },
                selected = selected,
                leading = { key ->
                    val option = state.themeOptions.firstOrNull { it.key == key }
                    if (option != null) ThemeSwatch(option) else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                },
                onSelect = { key ->
                    dialog = null
                    val option = state.themeOptions.firstOrNull { it.key == key }
                    if (option == null) {
                        themePrefs.useDynamic()
                    } else {
                        themePrefs.useSite(option.lightPrimary, option.darkPrimary)
                        if (option.key != state.selectedTheme || state.user?.preferredTheme.isNullOrEmpty()) {
                            onIntent(SettingsIntent.SetTheme(option.key))
                        }
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        "profile" -> ChoiceDialog(
            title = stringResource(R.string.settings_profile_shares),
            options = ShareLinksInProfile.values.map { it to profileSharesLabel(it) },
            selected = state.shareLinksInProfile,
            onSelect = {
                dialog = null
                if (it != state.shareLinksInProfile) onIntent(SettingsIntent.SetShareLinksInProfile(it))
            },
            onDismiss = { dialog = null },
        )
        "retention" -> VersionRetentionDialog(
            state = state,
            onConfirm = { enabled, exts, max ->
                dialog = null
                onIntent(SettingsIntent.SetVersionRetention(enabled, exts, max))
            },
            onDismiss = { dialog = null },
        )
    }
}

/** 主题色圆点（左半浅色主色、右半深色主色时按当前模式取一个） */
@Composable
private fun ThemeSwatch(option: ThemeColorOption) {
    val hex = if (isSystemInDarkTheme()) option.darkPrimary else option.lightPrimary
    val color = ThemePreferences.parseColor(hex)?.let { Color(it) } ?: Color.Gray
    Box(
        Modifier
            .size(24.dp)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun languageLabel(language: String?): String = when (language) {
    null -> stringResource(R.string.settings_language_system)
    "zh-CN", "zh-TW" -> stringResource(R.string.settings_language_zh)
    else -> stringResource(R.string.settings_language_en)
}

/** 系统（而非应用覆盖）语言对应的服务端代码 */
private fun systemServerLanguage(): String =
    if (Resources.getSystem().configuration.locales[0].language == "zh") "zh-CN" else "en-US"

@Composable
private fun profileSharesLabel(level: String): String = when (level) {
    ShareLinksInProfile.ALL -> stringResource(R.string.settings_profile_shares_all)
    ShareLinksInProfile.HIDE -> stringResource(R.string.settings_profile_shares_hide)
    else -> stringResource(R.string.settings_profile_shares_public)
}

@Composable
private fun versionRetentionSummary(state: SettingsState): String {
    val exts = if (state.versionRetentionExt.isEmpty()) {
        stringResource(R.string.settings_retention_all_types)
    } else {
        state.versionRetentionExt.joinToString(", ")
    }
    val max = if (state.versionRetentionMax == 0) {
        stringResource(R.string.settings_retention_unlimited)
    } else {
        stringResource(R.string.settings_retention_max_n, state.versionRetentionMax)
    }
    return "$exts · $max"
}

@Composable
private fun VersionRetentionDialog(
    state: SettingsState,
    onConfirm: (Boolean, List<String>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(state.versionRetentionEnabled) }
    var exts by rememberSaveable { mutableStateOf(state.versionRetentionExt.joinToString(", ")) }
    var max by rememberSaveable { mutableStateOf(state.versionRetentionMax.toString()) }
    val maxValue = max.trim().ifEmpty { "0" }.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_version_retention)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_retention_desc), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_retention_enable), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = exts,
                    onValueChange = { exts = it },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.settings_retention_exts)) },
                    supportingText = { Text(stringResource(R.string.settings_retention_exts_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = max,
                    onValueChange = { v -> max = v.filter { it.isDigit() }.take(6) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.settings_retention_max)) },
                    supportingText = { Text(stringResource(R.string.settings_retention_max_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = maxValue == null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = maxValue != null,
                onClick = { onConfirm(enabled, exts.split(',', ' ', '，').filter { it.isNotBlank() }, maxValue ?: 0) },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
