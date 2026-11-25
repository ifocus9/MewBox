package com.mewbox.tv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.mewbox.tv.ui.theme.MewBoxTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PLAYER_CONTROLS_ENTER_MS = 180
internal const val PLAYER_CONTROLS_EXIT_MS = 150
internal const val PLAYER_SHEET_ANIM_MS = 220

internal val PLAYER_SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

private val PlayerGlassDarkBase = Color(0xFF12141C)

internal val PlayerTopScrimBrush: Brush = Brush.verticalGradient(
    0f to Color(0xC7000000),
    0.35f to Color(0x66000000),
    0.72f to Color(0x1F000000),
    1f to Color.Transparent,
)

internal val PlayerBottomScrimBrush: Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.28f to Color(0x1F000000),
    0.65f to Color(0x66000000),
    1f to Color(0xC7000000),
)

/**
 * 播放器画面比例模式（映射 Media3 AspectRatioFrameLayout resizeMode）
 */
@OptIn(UnstableApi::class)
enum class PlayerAspectMode(
    val resizeMode: Int,
    val label: String,
) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "原始"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "铺满"),
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL, "拉伸"),
    ;

    companion object {
        fun fromResizeMode(mode: Int): PlayerAspectMode =
            entries.firstOrNull { it.resizeMode == mode } ?: FIT
    }
}

/**
 * 播放器无边框扁平暗色表面修饰符：
 * 去除了渐变高光与细描边，采用纯哑光半透暗色底（激活态使用浅主题色填充），
 * 在保证视频画面上清晰可读的同时保持克制、现代的扁平质感。
 */
internal fun Modifier.playerFlatSurface(
    shape: Shape,
    isActive: Boolean = false,
    baseAlpha: Float = 0.58f,
): Modifier = composed {
    val accent = MewBoxTheme.colors.panelAccent
    val baseColor = if (isActive) {
        accent.copy(alpha = 0.22f)
    } else {
        PlayerGlassDarkBase.copy(alpha = baseAlpha)
    }
    this
        .clip(shape)
        .background(baseColor)
}

/**
 * 将视频物理宽高转换为顶栏精简分辨率徽章（如 4K / 2K / 1080P / 720P）。
 */
internal fun formatResolutionBadge(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    val longEdge = maxOf(width, height)
    val shortEdge = minOf(width, height)
    return when {
        longEdge >= 3600 || shortEdge >= 2000 -> "4K"
        longEdge >= 2400 || shortEdge >= 1350 -> "2K"
        longEdge >= 1800 || shortEdge >= 1000 -> "1080P"
        longEdge >= 1200 || shortEdge >= 680 -> "720P"
        shortEdge >= 450 -> "480P"
        else -> "${width}×${height}"
    }
}

/**
 * 格式化倍速展示文本（如 1.0x / 1.25x / 2.0x）。
 */
internal fun formatSpeedLabel(speed: Float): String {
    val isSingleDecimal = abs(speed * 10f - (speed * 10f).roundToInt()) < 0.001f
    val formatted = if (isSingleDecimal) {
        String.format(Locale.US, "%.1f", speed)
    } else {
        String.format(Locale.US, "%.2f", speed)
    }
    return "${formatted}x"
}

/**
 * 格式化双指缩放倍率文本（如 1.3x / 1.25x）。
 */
internal fun formatZoomLabel(scale: Float): String = formatSpeedLabel(scale)

/**
 * 判断缩放倍率是否已偏离默认 1.0x。
 */
internal fun isZoomModified(scale: Float): Boolean = abs(scale - 1f) >= 0.03f
