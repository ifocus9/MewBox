package com.mewbox.tv.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 手势粗分类模式
 */
private enum class GestureMode {
    NONE,
    BOOST,
    DRAG,
    TAP,
}

/**
 * 拖动分类动作
 */
private enum class DragAction {
    SEEK,
    BRIGHTNESS,
    VOLUME,
}

/**
 * 播放器统一手势检测器
 *
 * 彻底隔离与互斥处理播放器复杂手势：
 * 1. 单击（显隐控制栏 / 锁定时唤醒解锁）
 * 2. 双击（播放/暂停切换）
 * 3. 长按倍速（按住屏幕中间主体内容区域加速，松手恢复）
 * 4. 水平滑动（快进 / 快退）
 * 5. 垂直滑动（左半屏亮度，右半屏音量）
 *
 * 互斥保障：
 * - 左右两侧边缘 15% 区域专属亮度/音量控制，不触发长按倍速；
 * - 任何滑动在达到 touchSlop 时立即取消长按定时器，杜绝滑动调节亮度和长按倍速同时触发；
 * - 长按倍速期间发生大幅度位移即退出倍速；
 * - 单击与双击采用严格时间窗口防冲突判定。
 */
suspend fun PointerInputScope.detectPlayerGestures(
    isLocked: () -> Boolean,
    isPlaying: () -> Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekChange: (deltaRatioX: Float) -> Unit,
    onSeekEnd: () -> Unit,
    onBrightnessStart: () -> Unit,
    onBrightnessChange: (deltaRatioY: Float) -> Unit,
    onBrightnessEnd: () -> Unit,
    onVolumeStart: () -> Unit,
    onVolumeChange: (deltaRatioY: Float) -> Unit,
    onVolumeEnd: () -> Unit,
) = coroutineScope {
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero
    var pendingSingleTapJob: Job? = null
    val doubleTapTimeout = 280L

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val downPosition = down.position
        val downTime = down.uptimeMillis
        val pointerId = down.id

        // 1. 锁屏模式：屏蔽双击、长按与滑动，仅检测单击用于唤出/隐藏解锁悬浮按钮
        if (isLocked()) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            var isTap = true
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    change.consume()
                    break
                }
                if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                    isTap = false
                }
            }
            if (isTap) {
                onSingleTap()
            }
            return@awaitEachGesture
        }

        // 2. 边缘与区域判定（对齐 FongMi 划分模式）
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val edgeThreshold = 24.dp.toPx()
        val isNearEdge = downPosition.x < edgeThreshold || downPosition.x > (width - edgeThreshold)
        if (isNearEdge) {
            return@awaitEachGesture
        }

        // 长按倍速专属区域：屏幕居中 70% 宽度（15% ~ 85%），避开左右两端专属调光/调音滑槽，且必须处于正在播放状态
        val isInBoostZone = downPosition.x >= (width * 0.15f) && downPosition.x <= (width * 0.85f)
        val playingNow = isPlaying()
        val canTriggerBoost = isInBoostZone && playingNow
        Timber.d("gesture: down at pos=(%.1f, %.1f), isInBoostZone=%s, isPlaying=%s, canTriggerBoost=%s", downPosition.x, downPosition.y, isInBoostZone, playingNow, canTriggerBoost)

        // 双击候选判定：距离上次点击在 280ms 内，且两击位移在容差范围内
        val isSecondTapCandidate = (downTime - lastTapTime) < doubleTapTimeout &&
            (downPosition - lastTapPosition).getDistance() < viewConfiguration.touchSlop * 3

        var gestureMode = GestureMode.NONE
        var dragStartChange: PointerInputChange? = null

        // 3. 初始手势判定（通过 withTimeout 等待长按，或优先捕获滑动/抬起）
        if (canTriggerBoost && !isSecondTapCandidate) {
            val isLongPress = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            gestureMode = GestureMode.TAP
                            break
                        }
                        val delta = change.position - downPosition
                        if (delta.getDistance() > viewConfiguration.touchSlop) {
                            // 发生位移：立即判定为拖动，取消长按等待
                            gestureMode = GestureMode.DRAG
                            dragStartChange = change
                            Timber.d("gesture: moved > touchSlop, abort boost -> DRAG")
                            break
                        }
                    }
                }
                false
            } catch (e: PointerEventTimeoutCancellationException) {
                // 超时到达且未移动、未松手，确认触发长按倍速
                Timber.d("gesture: long press timeout reached! -> BOOST")
                true
            }

            if (isLongPress) {
                gestureMode = GestureMode.BOOST
            }
        } else {
            // 在左侧亮度区、右侧音量区或双击等待期：纯滑动/点击判定，不启动长按定时器，彻底杜绝误触倍速
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    gestureMode = GestureMode.TAP
                    break
                }
                val delta = change.position - downPosition
                if (delta.getDistance() > viewConfiguration.touchSlop) {
                    gestureMode = GestureMode.DRAG
                    dragStartChange = change
                    break
                }
            }
        }

        Timber.d("gesture: resolved mode=%s", gestureMode)

        // 4. 根据判定结果执行互斥分支
        when (gestureMode) {
            GestureMode.BOOST -> {
                // 长按倍速开始
                pendingSingleTapJob?.cancel()
                pendingSingleTapJob = null
                onLongPressStart()

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    change.consume()
                    if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                        break
                    }
                }

                onLongPressEnd()
            }

            GestureMode.DRAG -> {
                // 拖动开始：取消挂起的单击任务
                pendingSingleTapJob?.cancel()
                pendingSingleTapJob = null

                val firstDrag = dragStartChange ?: down
                val deltaFromDown = firstDrag.position - downPosition
                val isHorizontal = abs(deltaFromDown.x) >= abs(deltaFromDown.y)

                // 水平滑动全屏统一为进度寻道；垂直滑动依左右半屏划分亮度与音量
                val dragAction = if (isHorizontal) {
                    DragAction.SEEK
                } else {
                    if (downPosition.x < width * 0.5f) {
                        DragAction.BRIGHTNESS
                    } else {
                        DragAction.VOLUME
                    }
                }

                when (dragAction) {
                    DragAction.SEEK -> onSeekStart()
                    DragAction.BRIGHTNESS -> onBrightnessStart()
                    DragAction.VOLUME -> onVolumeStart()
                }

                var currentChange = firstDrag
                while (true) {
                    currentChange.consume()
                    val currentPos = currentChange.position
                    when (dragAction) {
                        DragAction.SEEK -> {
                            val deltaRatioX = (currentPos.x - downPosition.x) / width
                            onSeekChange(deltaRatioX)
                        }
                        DragAction.BRIGHTNESS -> {
                            // 向上滑动为正，向下滑动为负
                            val deltaRatioY = (downPosition.y - currentPos.y) / height
                            onBrightnessChange(deltaRatioY)
                        }
                        DragAction.VOLUME -> {
                            // 向上滑动为正，向下滑动为负
                            val deltaRatioY = (downPosition.y - currentPos.y) / height
                            onVolumeChange(deltaRatioY)
                        }
                    }

                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (dragAction == DragAction.SEEK) {
                            val finalRatioX = (change.position.x - downPosition.x) / width
                            onSeekChange(finalRatioX)
                        }
                        change.consume()
                        break
                    }
                    currentChange = change
                }

                when (dragAction) {
                    DragAction.SEEK -> onSeekEnd()
                    DragAction.BRIGHTNESS -> onBrightnessEnd()
                    DragAction.VOLUME -> onVolumeEnd()
                }
            }

            GestureMode.TAP -> {
                if (isSecondTapCandidate) {
                    // 命中双击：取消单击，触发播放/暂停
                    pendingSingleTapJob?.cancel()
                    pendingSingleTapJob = null
                    lastTapTime = 0L
                    lastTapPosition = Offset.Zero
                    onDoubleTap()
                } else {
                    // 记录首击，并启动双击等待定时器
                    lastTapTime = downTime
                    lastTapPosition = downPosition
                    pendingSingleTapJob?.cancel()
                    pendingSingleTapJob = launch {
                        delay(doubleTapTimeout)
                        onSingleTap()
                        lastTapTime = 0L
                        lastTapPosition = Offset.Zero
                    }
                }
            }

            GestureMode.NONE -> {}
        }
    }
}

suspend fun PointerInputScope.detectPlayerGestures(
    isLocked: Boolean,
    isPlaying: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekChange: (deltaRatioX: Float) -> Unit,
    onSeekEnd: () -> Unit,
    onBrightnessStart: () -> Unit,
    onBrightnessChange: (deltaRatioY: Float) -> Unit,
    onBrightnessEnd: () -> Unit,
    onVolumeStart: () -> Unit,
    onVolumeChange: (deltaRatioY: Float) -> Unit,
    onVolumeEnd: () -> Unit,
) = detectPlayerGestures(
    isLocked = { isLocked },
    isPlaying = { isPlaying },
    onSingleTap = onSingleTap,
    onDoubleTap = onDoubleTap,
    onLongPressStart = onLongPressStart,
    onLongPressEnd = onLongPressEnd,
    onSeekStart = onSeekStart,
    onSeekChange = onSeekChange,
    onSeekEnd = onSeekEnd,
    onBrightnessStart = onBrightnessStart,
    onBrightnessChange = onBrightnessChange,
    onBrightnessEnd = onBrightnessEnd,
    onVolumeStart = onVolumeStart,
    onVolumeChange = onVolumeChange,
    onVolumeEnd = onVolumeEnd,
)

