package com.mewbox.tv.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.mewbox.tv.ui.theme.MewBoxTheme

private val ProgressThumbIdleRadius = 6.dp
private val ProgressThumbDragRadius = 8.5.dp
private val ProgressTrackIdleHeight = 3.5.dp
private val ProgressTrackDragHeight = 5.5.dp

/**
 * 现代视频播放器全宽细条进度条：
 * - 拖拽时轨道与滑块平滑放大并带柔和光晕 + 触觉反馈
 * - 首尾预留滑块半径安全边距，防止 0% / 100% 滑块被裁切
 * - 支持绘制片头/片尾跳过位置主题色刻度点
 */
@Composable
internal fun VideoPlayerProgressBar(
    progress: Float,
    bufferedProgress: Float,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    openingRatio: Float? = null,
    endingRatio: Float? = null,
    onDraggingChanged: (Boolean) -> Unit = {},
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentProgress = (if (isDragging) dragProgress else progress).coerceIn(0f, 1f)
    val buffered = bufferedProgress.coerceIn(0f, 1f)

    val thumbRadius by animateDpAsState(
        targetValue = if (isDragging) ProgressThumbDragRadius else ProgressThumbIdleRadius,
        animationSpec = tween(durationMillis = 120),
        label = "progressThumbRadius",
    )
    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) ProgressTrackDragHeight else ProgressTrackIdleHeight,
        animationSpec = tween(durationMillis = 120),
        label = "progressTrackHeight",
    )

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val edgePadPx = with(density) { ProgressThumbDragRadius.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val haloRadiusPx = with(density) { (thumbRadius + 4.dp).toPx() }
    val markerRadiusPx = with(density) { 2.5.dp.toPx() }
    val accentColor = MewBoxTheme.colors.panelAccent

    val currentOnSeekStart by rememberUpdatedState(onSeekStart)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    val currentOnDraggingChanged by rememberUpdatedState(onDraggingChanged)

    fun mapPositionToRatio(x: Float, width: Float): Float {
        val usable = (width - edgePadPx * 2f).coerceAtLeast(1f)
        return ((x - edgePadPx) / usable).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .height(22.dp)
            .pointerInput(edgePadPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val initialProgress = mapPositionToRatio(down.position.x, width)
                        dragProgress = initialProgress
                        if (!isDragging) {
                            isDragging = true
                            currentOnDraggingChanged(true)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        currentOnSeekStart()
                        currentOnSeek(initialProgress)
                    }

                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            change?.consume()
                            if (isDragging) {
                                isDragging = false
                                currentOnDraggingChanged(false)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnSeekFinished(dragProgress)
                            }
                            break
                        } else {
                            change.consume()
                            val widthNow = size.width.toFloat()
                            if (widthNow > 0f) {
                                val newProgress = mapPositionToRatio(change.position.x, widthNow)
                                dragProgress = newProgress
                                currentOnSeek(newProgress)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(),
        ) {
            val startX = edgePadPx.coerceAtMost(size.width / 2f)
            val endX = (size.width - edgePadPx).coerceAtLeast(startX)
            val usableWidth = (endX - startX).coerceAtLeast(1f)
            val centerY = size.height / 2f

            // 1. 底轨
            drawLine(
                color = Color.White.copy(alpha = 0.24f),
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            // 2. 缓冲轨
            if (buffered > 0f) {
                val bufferEnd = startX + usableWidth * buffered
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(startX, centerY),
                    end = Offset(bufferEnd, centerY),
                    strokeWidth = trackHeightPx,
                    cap = StrokeCap.Round,
                )
            }

            // 3. 已播放轨道
            val activeEnd = startX + usableWidth * currentProgress
            if (currentProgress > 0f) {
                drawLine(
                    color = Color.White,
                    start = Offset(startX, centerY),
                    end = Offset(activeEnd, centerY),
                    strokeWidth = trackHeightPx,
                    cap = StrokeCap.Round,
                )
            }

            // 4. 片头 / 片尾刻度点
            openingRatio?.takeIf { it > 0f && it < 1f }?.let { ratio ->
                drawCircle(
                    color = accentColor,
                    radius = markerRadiusPx,
                    center = Offset(startX + usableWidth * ratio, centerY),
                )
            }
            endingRatio?.takeIf { it > 0f && it < 1f }?.let { ratio ->
                drawCircle(
                    color = accentColor,
                    radius = markerRadiusPx,
                    center = Offset(startX + usableWidth * ratio, centerY),
                )
            }

            // 5. 滑块光晕与实心球
            if (isDragging) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.26f),
                    radius = haloRadiusPx,
                    center = Offset(activeEnd, centerY),
                )
            }
            drawCircle(
                color = Color.White,
                radius = thumbRadiusPx,
                center = Offset(activeEnd, centerY),
            )
        }
    }
}

/**
 * 无控制栏时水平滑屏快进的极细预览进度条
 */
@Composable
internal fun PlayerSeekPreviewStrip(
    progress: Float,
    bufferedProgress: Float,
    modifier: Modifier = Modifier,
    openingRatio: Float? = null,
    endingRatio: Float? = null,
) {
    val accentColor = MewBoxTheme.colors.panelAccent
    val clamped = progress.coerceIn(0f, 1f)
    val buffered = bufferedProgress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val strokePx = 3.dp.toPx()
        val centerY = size.height / 2f
        val width = size.width

        drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        if (buffered > 0f) {
            drawLine(
                color = Color.White.copy(alpha = 0.42f),
                start = Offset(0f, centerY),
                end = Offset(width * buffered, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
        if (clamped > 0f) {
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(width * clamped, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
        openingRatio?.takeIf { it > 0f && it < 1f }?.let { ratio ->
            drawCircle(
                color = accentColor,
                radius = 2.dp.toPx(),
                center = Offset(width * ratio, centerY),
            )
        }
        endingRatio?.takeIf { it > 0f && it < 1f }?.let { ratio ->
            drawCircle(
                color = accentColor,
                radius = 2.dp.toPx(),
                center = Offset(width * ratio, centerY),
            )
        }
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = Offset(width * clamped, centerY),
        )
    }
}
