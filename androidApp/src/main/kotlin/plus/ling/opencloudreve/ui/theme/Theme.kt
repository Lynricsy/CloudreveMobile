package plus.ling.opencloudreve.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 品牌色 #0070F0 生成的 M3 浅色配色（Android 12 以下或关闭动态取色时使用） */
private val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF005AC1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    inversePrimary = Color(0xFFADC6FF),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF715573),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBD7FC),
    onTertiaryContainer = Color(0xFF29132D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Color(0xFF005AC1),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

/** 品牌色 #0070F0 生成的 M3 深色配色 */
private val BrandDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD8E2FF),
    inversePrimary = Color(0xFF005AC1),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDEBCDF),
    onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF583E5B),
    onTertiaryContainer = Color(0xFFFBD7FC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFADC6FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/**
 * 应用主题：默认 Android 12+ 使用系统动态取色，否则用品牌色配色；[settings] 选择站点主题色时，
 * 以其主色（深浅色各一）为种子按 M3 HCT 算法生成配色。跟随系统深色模式。
 */
@Composable
fun OpenCloudreveTheme(
    settings: ThemeSettings = ThemeSettings(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val seed = if (settings.source == ThemeSource.Site) {
        if (darkTheme) settings.darkSeed ?: settings.lightSeed else settings.lightSeed
    } else {
        null
    }
    val colorScheme = when {
        seed != null -> remember(seed, darkTheme) { seedColorScheme(seed, darkTheme) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** 以 [seed]（ARGB）生成 M3 配色；用 Content 方案，主色尽量贴近站点主题色本身 */
fun seedColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val s = SchemeContent(Hct.fromInt(seed), dark, 0.0)
    val c = { argb: Int -> Color(argb) }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(s.primary),
        onPrimary = c(s.onPrimary),
        primaryContainer = c(s.primaryContainer),
        onPrimaryContainer = c(s.onPrimaryContainer),
        inversePrimary = c(s.inversePrimary),
        secondary = c(s.secondary),
        onSecondary = c(s.onSecondary),
        secondaryContainer = c(s.secondaryContainer),
        onSecondaryContainer = c(s.onSecondaryContainer),
        tertiary = c(s.tertiary),
        onTertiary = c(s.onTertiary),
        tertiaryContainer = c(s.tertiaryContainer),
        onTertiaryContainer = c(s.onTertiaryContainer),
        error = c(s.error),
        onError = c(s.onError),
        errorContainer = c(s.errorContainer),
        onErrorContainer = c(s.onErrorContainer),
        background = c(s.background),
        onBackground = c(s.onBackground),
        surface = c(s.surface),
        onSurface = c(s.onSurface),
        surfaceVariant = c(s.surfaceVariant),
        onSurfaceVariant = c(s.onSurfaceVariant),
        surfaceTint = c(s.surfaceTint),
        inverseSurface = c(s.inverseSurface),
        inverseOnSurface = c(s.inverseOnSurface),
        outline = c(s.outline),
        outlineVariant = c(s.outlineVariant),
        scrim = c(s.scrim),
        surfaceBright = c(s.surfaceBright),
        surfaceDim = c(s.surfaceDim),
        surfaceContainerLowest = c(s.surfaceContainerLowest),
        surfaceContainerLow = c(s.surfaceContainerLow),
        surfaceContainer = c(s.surfaceContainer),
        surfaceContainerHigh = c(s.surfaceContainerHigh),
        surfaceContainerHighest = c(s.surfaceContainerHighest),
    )
}
