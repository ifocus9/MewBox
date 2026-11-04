package com.mewbox.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BluePrimary = Color(0xFF217AF4)
val BlueTypeIndicator = Color(0xFF1E88E5)
val OrangeBadge = Color(0xFFFA8C16)

private val Dark = darkColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    secondary = Color(0xFF5B9DFF),
    background = Color(0xFF121214),
    surface = Color(0xFF121214),
    surfaceVariant = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF18181A),
    surfaceContainerHigh = Color(0xFF242426),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF8E8E95),
)

private val Light = lightColorScheme(
    primary = Color(0xFF217AF4),
    onPrimary = Color.White,
    secondary = Color(0xFF1E88E5),
    onSecondaryContainer = Color(0xFF1E88E5),
    background = Color(0xFFEDF1F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFF5F6F8),
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainerHigh = Color(0xFFEBEBF0),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF2C2C2E),
)

val MewBoxDarkColorScheme = Dark
val MewBoxLightColorScheme = Light

/**
 * 软件全局主题配置
 * 默认强制不受系统深色模式控制，锁定为标准清爽亮色主题（纯白底 + 主题蓝 + 橙色徽章）
 * 支持通过 colorScheme 参数在海报详情页等场景局部注入动态主题配色
 */
@Composable
fun MewBoxTheme(
    darkTheme: Boolean = false,
    colorScheme: androidx.compose.material3.ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val resolvedScheme = colorScheme ?: if (darkTheme) Dark else Light
    MaterialTheme(colorScheme = resolvedScheme, content = content)
}
