package com.mewbox.tv.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.mewbox.tv.ui.navbar.InteractiveHighlight
import com.mewbox.tv.ui.theme.LiquidGlassDefaults
import kotlin.math.min

val LocalTopBarGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 方案 A：纯 Compose 拟态质感伪玻璃修饰符（全版本通用，零 GPU 额外开销）
 * 包含：半透亚克力基色 + 顶部微光照漫反射渐变 + 0.75dp 菲涅尔双色高光渐变描边
 */
@Composable
fun Modifier.pseudoGlass(
    shape: Shape,
    baseColor: Color = Color.White.copy(alpha = 0.75f),
    strokeWidth: Dp = 0.75.dp,
): Modifier = this
    .clip(shape)
    .background(baseColor)
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.35f),
            0.5f to Color.Transparent,
        )
    )
    .border(
        width = strokeWidth,
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.75f),
                Color.White.copy(alpha = 0.18f),
            )
        ),
        shape = shape,
    )

/**
 * 自适应通用玻璃表面修饰符：
 * - 在支持的设备（Android 12+）上：运行真实的 Backdrop 模糊与微透镜折射
 * - 在不支持的设备（Android 9 等模拟器/低版本）上：自动无缝降级为方案 A 拟态玻璃
 * 业务 UI 层可直接调用，无需编写任何版本分支代码。
 */
@Composable
fun Modifier.glassTopBarSurface(
    shape: Shape,
    fallbackColor: Color = Color.White.copy(alpha = 0.75f),
    pressEffect: Boolean = true,
): Modifier = glassSurface(LocalTopBarGlassBackdrop.current ?: emptyBackdrop(), shape, fallbackColor, pressEffect)

@Composable
fun Modifier.glassSurface(
    shape: Shape,
    fallbackColor: Color = Color.White.copy(alpha = 0.75f),
    pressEffect: Boolean = true,
): Modifier = glassSurface(emptyBackdrop(), shape, fallbackColor, pressEffect)

@Composable
fun Modifier.glassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    fallbackColor: Color = Color.White.copy(alpha = 0.75f),
    pressEffect: Boolean = true,
): Modifier {
    val config = LiquidGlassDefaults.Config
    val glassEnabled = backdrop != null &&
        config.controlsEnabled &&
        LiquidGlassDefaults.isSupported
    if (!glassEnabled) {
        val baseColor = if (fallbackColor != Color.Unspecified) fallbackColor else Color.White.copy(alpha = 0.75f)
        return this.pseudoGlass(shape = shape, baseColor = baseColor)
    }

    val density = LocalDensity.current
    val blurPx = with(density) { config.blurDp.dp.toPx() }
    val distortionPx = with(density) { config.distortionDp.dp.toPx() }
    val supportsLens = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
        alpha = 0.4f * config.containerAlphaScale
    )
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight =
        if (pressEffect) remember(animationScope) { InteractiveHighlight(animationScope) } else null

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(
                brightness = config.contentBrightness,
                contrast = config.contentContrast,
                saturation = config.contentSaturation,
            )
            blur(blurPx)
            if (supportsLens) {
                val refraction = min(distortionPx, size.minDimension / 2f)
                lens(
                    refraction,
                    refraction,
                    chromaticAberration = config.dispersion,
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = {
            Shadow(
                radius = 8.dp,
                color = Color.Black.copy(0.1f),
            )
        },
        layerBlock = interactiveHighlight?.let(::pressGrowthLayerBlock),
        onDrawSurface = { drawRect(containerColor) },
    )
        .then(interactiveHighlight?.modifier ?: Modifier)
        .then(interactiveHighlight?.gestureModifier ?: Modifier)
}

private fun pressGrowthLayerBlock(
    interactiveHighlight: InteractiveHighlight,
): GraphicsLayerScope.() -> Unit = {
    val progress = interactiveHighlight.pressProgress
    if (progress > 0f && size.height > 0f && size.width > 0f) {
        val growthPx = 4.dp.toPx() * progress
        scaleY = 1f + growthPx / size.height
        scaleX = 1f + growthPx / size.maxDimension
    }
}

