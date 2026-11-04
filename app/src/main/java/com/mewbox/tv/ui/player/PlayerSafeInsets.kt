package com.mewbox.tv.ui.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放器操作展示层统一安全区度量值
 *
 * 1. [horizontal]: 左右对称安全边距。取两侧 Cutout 与安全区最大值，
 *    左右两端严格对称避让，彻底解决单侧挖孔导致横屏下进度条与控制栏视觉左右倾斜不对称的问题。
 * 2. [top]: 顶部安全边距。竖屏全屏模式下完整避让顶部挖孔与状态栏，横屏下仅避让物理 Cutout。
 * 3. [bottom]: 底部安全边距。沉浸式全屏隐藏系统栏后仍强制避让底部手势横条与物理边缘，
 *    避免操作按钮贴底或误触系统 Home 手势。
 */
@Immutable
internal data class PlayerSafeInsets(
    val horizontal: Dp,
    val top: Dp,
    val bottom: Dp,
)

@Composable
internal fun rememberPlayerSafeInsets(): PlayerSafeInsets {
    val layoutDirection = LocalLayoutDirection.current
    val config = LocalConfiguration.current
    val isPortrait = config.screenHeightDp > config.screenWidthDp

    // 1. 左右安全区（横向严格对称）
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val safeDrawingInsets = WindowInsets.safeDrawing.asPaddingValues()

    val leftInset = maxOf(
        cutoutInsets.calculateLeftPadding(layoutDirection),
        safeDrawingInsets.calculateLeftPadding(layoutDirection),
    )
    val rightInset = maxOf(
        cutoutInsets.calculateRightPadding(layoutDirection),
        safeDrawingInsets.calculateRightPadding(layoutDirection),
    )
    val symmetricHorizontal = maxOf(leftInset, rightInset)

    // 2. 顶部安全区（竖屏避开挖孔与状态栏，横屏按需避让）
    val topInset = if (isPortrait) {
        listOf(
            cutoutInsets.calculateTopPadding(),
            safeDrawingInsets.calculateTopPadding(),
            WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding(),
        ).maxOrNull() ?: 0.dp
    } else {
        maxOf(
            cutoutInsets.calculateTopPadding(),
            safeDrawingInsets.calculateTopPadding(),
        )
    }

    // 3. 底部安全区（沉浸式全屏隐藏系统栏后 safeDrawing.bottom 会降为 0，通过 ignoringVisibility / mandatoryGestures 取值）
    val navBarsIgnoringVisBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val mandatoryGesturesBottom = WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding()
    val safeDrawingBottom = safeDrawingInsets.calculateBottomPadding()
    val cutoutBottom = cutoutInsets.calculateBottomPadding()
    val baselineBottom = if (isPortrait) 20.dp else 12.dp

    val bottomInset = listOf(
        navBarsIgnoringVisBottom,
        mandatoryGesturesBottom,
        safeDrawingBottom,
        cutoutBottom,
        baselineBottom,
    ).maxOrNull() ?: baselineBottom

    return remember(symmetricHorizontal, topInset, bottomInset) {
        PlayerSafeInsets(
            horizontal = symmetricHorizontal,
            top = topInset,
            bottom = bottomInset,
        )
    }
}

