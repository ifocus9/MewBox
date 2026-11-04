package com.mewbox.tv.ui.player

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 视频播放器精致细条进度条
 * 样式参考截图：
 * 1. 3.dp 高度细腻轨道，两端圆角
 * 2. 纯白高亮已播放轨道与半透明白色底轨/缓冲轨
 * 3. 12.dp 纯白实心圆球滑块，拖拽时适度微放大反馈
 * 4. 具备充足的触控热区，支持点击跳转与平滑拖拽寻道
 */
@Composable
internal fun VideoPlayerProgressBar(
    progress: Float,
    bufferedProgress: Float,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentProgress = (if (isDragging) dragProgress else progress).coerceIn(0f, 1f)
    val buffered = bufferedProgress.coerceIn(0f, 1f)

    val thumbRadius = if (isDragging) 8.dp else 7.dp
    val trackHeight = 3.5.dp

    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val trackHeightPx = with(density) { trackHeight.toPx() }

    val currentOnSeekStart by rememberUpdatedState(onSeekStart)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)

    Box(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width > 0) {
                        val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                        dragProgress = initialProgress
                        isDragging = true
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
                                currentOnSeekFinished(dragProgress)
                            }
                            break
                        } else {
                            change.consume()
                            val widthNow = size.width.toFloat()
                            if (widthNow > 0) {
                                val newProgress = (change.position.x / widthNow).coerceIn(0f, 1f)
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
            val width = size.width
            val centerY = size.height / 2f

            // 1. 底轨（未播放部分：半透明纯白）
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            // 2. 缓冲轨（略亮的半透明白）
            if (buffered > 0f) {
                val bufferEnd = (width * buffered).coerceAtMost(width)
                drawLine(
                    color = Color.White.copy(alpha = 0.48f),
                    start = Offset(0f, centerY),
                    end = Offset(bufferEnd, centerY),
                    strokeWidth = trackHeightPx,
                    cap = StrokeCap.Round,
                )
            }

            // 3. 播放轨（纯白色高亮）
            val activeEnd = (width * currentProgress).coerceIn(0f, width)
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(activeEnd, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )

            // 4. 滑块 Thumb（纯白实心圆球）
            drawCircle(
                color = Color.White,
                radius = thumbRadiusPx,
                center = Offset(activeEnd, centerY),
            )
        }
    }
}

