package com.mewbox.tv.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.mewbox.tv.bean.ThemeColor
import com.mewbox.tv.bean.ThemeMode

/** 默认品牌主色（绯红） */
val BrandPrimary = Color(ThemeColor.DEFAULT_ARGB)

const val LIGHT_WINDOW_BG_ARGB = 0xFFF2F3F7.toInt()
const val DARK_WINDOW_BG_ARGB = 0xFF0E0F12.toInt()

private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF16181D)
private val LightContainerDarker = Color(0xFF0E1117)
private val DarkSurface = Color(0xFF17191E)

@Immutable
data class MewBoxExtendedColors(
    val floatingCardBg: Color,
    val searchCardBg: Color,
    val cardBorder: Color,
    val subtleBorder: Color,
    val primaryBorder: Color,
    val primarySoftBg: Color,
    val panelAccent: Color,
    val accentOnDark: Color,
    val successBg: Color,
    val successText: Color,
    val errorBg: Color,
    val errorText: Color,
    val glassFallback: Color,
    val glassGlossTop: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val navPillIndicator: Color,
    val navBarFallbackBg: Color,
    val scrim: Color,
    val posterPlaceholderBg: Color,
    val chevron: Color,
    val searchBarFallback: Color,
)

/** 根据主题色亮度自动选择高对比度前景色（黑字或白字） */
fun contrastOnAccent(accent: Color): Color =
    if (accent.luminance() > 0.58f) LightOnSurface else Color.White

private fun resolveLightPrimary(accentArgb: Int): Color {
    val base = Color(ThemeColor.normalizeArgb(accentArgb))
    return if (base.luminance() > 0.85f) {
        lerp(base, LightOnSurface, 0.22f)
    } else {
        base
    }
}

private fun resolveOnLightContainer(primary: Color): Color {
    val candidate = lerp(primary, LightContainerDarker, 0.14f)
    if (candidate.luminance() <= 0.28f) return candidate
    val hsv = ThemeColor.argbToHsv(candidate.toArgb())
    var value = hsv.value * 0.82f
    while (value >= 0.14f) {
        val darkened = Color(ThemeColor.hsvToArgb(hsv.hue, hsv.saturation, value))
        if (darkened.luminance() <= 0.28f) return darkened
        value *= 0.82f
    }
    return lerp(candidate, LightOnSurface, 0.65f)
}

private fun resolveDarkPrimary(accentArgb: Int): Color {
    val base = Color(ThemeColor.normalizeArgb(accentArgb))
    val lum = base.luminance()
    return when {
        lum < 0.08f -> lerp(base, Color.White, 0.42f)
        lum < 0.35f -> lerp(base, Color.White, 0.06f)
        else -> base
    }
}

fun buildLightColorScheme(accentArgb: Int): ColorScheme {
    val primary = resolveLightPrimary(accentArgb)
    val onPrimary = contrastOnAccent(primary)
    val primaryContainer = lerp(LightSurface, primary, 0.09f)
    val onPrimaryContainer = resolveOnLightContainer(primary)
    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = primary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        background = Color(0xFFF2F3F7),
        onBackground = LightOnSurface,
        surface = LightSurface,
        surfaceBright = LightSurface,
        surfaceVariant = Color(0xFFEFF1F5),
        surfaceContainerLowest = LightSurface,
        surfaceContainerLow = LightSurface,
        surfaceContainer = Color(0xFFF7F8FA),
        surfaceContainerHigh = Color(0xFFE8EBF0),
        surfaceContainerHighest = Color(0xFFE2E5EC),
        onSurface = LightOnSurface,
        onSurfaceVariant = Color(0xFF646B78),
        outline = Color(0xFF8B92A0),
        outlineVariant = Color(0xFFE6E9F0),
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEF0F0),
        onErrorContainer = Color(0xFFC81E1E),
    )
}

fun buildDarkColorScheme(accentArgb: Int): ColorScheme {
    val primary = resolveDarkPrimary(accentArgb)
    val onPrimary = contrastOnAccent(primary)
    val primaryContainer = lerp(DarkSurface, primary, 0.18f)
    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = lerp(primary, Color.White, 0.32f),
        secondary = lerp(primary, Color.White, 0.18f),
        secondaryContainer = primaryContainer,
        onSecondaryContainer = lerp(primary, Color.White, 0.12f),
        background = Color(0xFF0E0F12),
        onBackground = Color(0xFFF2F4F8),
        surface = DarkSurface,
        surfaceBright = Color(0xFF2B303B),
        surfaceVariant = Color(0xFF22252D),
        surfaceContainerLowest = Color(0xFF0A0B0E),
        surfaceContainerLow = DarkSurface,
        surfaceContainer = Color(0xFF1E2128),
        surfaceContainerHigh = Color(0xFF262A33),
        surfaceContainerHighest = Color(0xFF2F3440),
        onSurface = Color(0xFFF2F4F8),
        onSurfaceVariant = Color(0xFF98A0AE),
        outline = Color(0xFF555C6B),
        outlineVariant = Color(0xFF2A2E38),
        error = Color(0xFFFF6B6B),
        onError = Color.White,
        errorContainer = Color(0xFF3A1B1E),
        onErrorContainer = Color(0xFFFF8A8A),
    )
}

fun buildLightExtendedColors(accentArgb: Int): MewBoxExtendedColors {
    val lightPrimary = resolveLightPrimary(accentArgb)
    val darkPrimary = resolveDarkPrimary(accentArgb)
    return MewBoxExtendedColors(
        floatingCardBg = LightSurface,
        searchCardBg = Color(0xFFF6F8FB),
        cardBorder = Color(0xFFE6E9F0),
        subtleBorder = Color(0x120F172A),
        primaryBorder = lerp(LightSurface, lightPrimary, 0.28f),
        primarySoftBg = lerp(LightSurface, lightPrimary, 0.09f),
        panelAccent = darkPrimary,
        accentOnDark = lerp(darkPrimary, Color.White, 0.35f),
        successBg = Color(0xFFEAF7EF),
        successText = Color(0xFF1F7A47),
        errorBg = Color(0xFFFEF0F0),
        errorText = Color(0xFFC81E1E),
        glassFallback = Color.White.copy(alpha = 0.88f),
        glassGlossTop = Color.White.copy(alpha = 0.45f),
        glassBorderTop = Color.White.copy(alpha = 0.85f),
        glassBorderBottom = Color(0x140F172A),
        navPillIndicator = Color(0x100F172A),
        navBarFallbackBg = Color.White.copy(alpha = 0.90f),
        scrim = Color(0xFF0E1117).copy(alpha = 0.34f),
        posterPlaceholderBg = Color(0xFFE8EBF1),
        chevron = Color(0xFFB8BFC9),
        searchBarFallback = LightSurface,
    )
}

fun buildDarkExtendedColors(accentArgb: Int): MewBoxExtendedColors {
    val darkPrimary = resolveDarkPrimary(accentArgb)
    return MewBoxExtendedColors(
        floatingCardBg = Color(0xFF1F222A),
        searchCardBg = Color(0xFF1F222A),
        cardBorder = Color(0xFF2A2E38),
        subtleBorder = Color(0x1FFFFFFF),
        primaryBorder = lerp(DarkSurface, darkPrimary, 0.42f),
        primarySoftBg = lerp(DarkSurface, darkPrimary, 0.18f),
        panelAccent = darkPrimary,
        accentOnDark = lerp(darkPrimary, Color.White, 0.35f),
        successBg = Color(0xFF163322),
        successText = Color(0xFF6EE7A0),
        errorBg = Color(0xFF3A1B1E),
        errorText = Color(0xFFFF8A8A),
        glassFallback = Color(0xFF22252D).copy(alpha = 0.88f),
        glassGlossTop = Color.White.copy(alpha = 0.08f),
        glassBorderTop = Color.White.copy(alpha = 0.18f),
        glassBorderBottom = Color.White.copy(alpha = 0.05f),
        navPillIndicator = Color(0x24FFFFFF),
        navBarFallbackBg = Color(0xFF1E2128).copy(alpha = 0.92f),
        scrim = Color.Black.copy(alpha = 0.58f),
        posterPlaceholderBg = Color(0xFF20232B),
        chevron = Color(0xFF555C6B),
        searchBarFallback = Color(0xFF22252D),
    )
}

private val LocalMewBoxColors = staticCompositionLocalOf { buildLightExtendedColors(ThemeColor.DEFAULT_ARGB) }
private val LocalIsDarkTheme = staticCompositionLocalOf { false }
private val LocalThemeAccentArgb = staticCompositionLocalOf { ThemeColor.DEFAULT_ARGB }

object MewBoxTheme {
    val colors: MewBoxExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMewBoxColors.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDarkTheme.current
}

/**
 * 同步系统级应用夜间模式（Android 12+），使冷启动窗体与系统启动屏匹配当前主题偏好。
 */
fun applyApplicationNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        val nightMode = when (mode) {
            ThemeMode.Auto -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
        }
        uiModeManager?.setApplicationNightMode(nightMode)
    }
}

/**
 * 软件全局主题配置
 * 支持浅色 / 深色主题、全局自定义主题色与扩展语义色令牌，并允许在详情页等场景局部注入海报动态配色。
 */
@Composable
fun MewBoxTheme(
    darkTheme: Boolean = false,
    accentArgb: Int = LocalThemeAccentArgb.current,
    colorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val resolvedAccent = remember(accentArgb) { ThemeColor.normalizeArgb(accentArgb) }
    val resolvedScheme = colorScheme ?: remember(darkTheme, resolvedAccent) {
        if (darkTheme) buildDarkColorScheme(resolvedAccent) else buildLightColorScheme(resolvedAccent)
    }
    val extendedColors = remember(darkTheme, resolvedAccent) {
        if (darkTheme) buildDarkExtendedColors(resolvedAccent) else buildLightExtendedColors(resolvedAccent)
    }
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalThemeAccentArgb provides resolvedAccent,
        LocalMewBoxColors provides extendedColors,
    ) {
        MaterialTheme(colorScheme = resolvedScheme, content = content)
    }
}
