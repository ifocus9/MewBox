package com.mewbox.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.player.Players
import java.util.Locale

/**
 * 播放器轻量缓冲 / 加载提示浮层（对齐 FongMi/TV 的 view_progress 极简轻量转圈与实时网速样式）
 * 无多余背景卡片遮挡画面，中央白色轻量圆弧转圈 + 底部实时下行速率 / 检索状态
 */
@Composable
internal fun PlayerLoadingOverlay(
    isResolvingPlay: Boolean,
    isAutoSearching: Boolean,
    autoSearchMessage: String?,
    networkSpeed: String = "",
) {
    val displayText = when {
        isAutoSearching -> autoSearchMessage ?: "搜索中"
        isResolvingPlay -> "加载中"
        networkSpeed.isNotBlank() -> networkSpeed
        else -> ""
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Color.White,
                strokeWidth = 3.dp,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round,
            )
            if (displayText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displayText,
                    color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.65f),
                            offset = Offset(0f, 1f),
                            blurRadius = 2f,
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * 播放错误提示浮层
 */
@Composable
internal fun PlayerErrorOverlay(
    errorMessage: String?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xCC1A1A1A),
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = "播放异常",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = errorMessage ?: "解析或播放直链失败",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 手势浮层 HUD（快进快退、亮度、音量、长按倍速）
 */
@Composable
internal fun BoxScope.PlayerGestureHud(
    isSeekingByGesture: Boolean,
    gestureSeekTargetMs: Long,
    gestureSeekDeltaMs: Long,
    isBrightnessAdjusting: Boolean,
    currentBrightness: Float,
    isVolumeAdjusting: Boolean,
    currentVolumeRatio: Float,
    isBoostPlaying: Boolean,
    boostSpeed: Float,
) {
    // 长按倍速播放提示：复用统一的手势 HUD 胶囊，与快进 / 亮度 / 音量保持一致
    GestureHudPill(visible = isBoostPlaying) {
        Text(
            text = formatPlaybackSpeed(boostSpeed),
            color = Color.White,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
    }

    GestureHudPill(visible = isSeekingByGesture) {
        Icon(
            imageVector = if (gestureSeekDeltaMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        val sign = if (gestureSeekDeltaMs >= 0) "+" else ""
        Text(
            text = "${Players.formatDuration(gestureSeekTargetMs)} ($sign${gestureSeekDeltaMs / 1000}s)",
            color = Color.White,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
    }

    GestureHudPill(visible = isBrightnessAdjusting) {
        Icon(
            imageVector = if (currentBrightness > 0.5f) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        LinearProgressIndicator(
            progress = { currentBrightness },
            modifier = Modifier
                .width(100.dp)
                .height(4.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
        )
    }

    GestureHudPill(visible = isVolumeAdjusting) {
        val volumeIcon = when {
            currentVolumeRatio <= 0.05f -> Icons.AutoMirrored.Filled.VolumeMute
            currentVolumeRatio > 0.5f -> Icons.AutoMirrored.Filled.VolumeUp
            else -> Icons.AutoMirrored.Filled.VolumeDown
        }
        Icon(
            imageVector = volumeIcon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        LinearProgressIndicator(
            progress = { currentVolumeRatio },
            modifier = Modifier
                .width(100.dp)
                .height(4.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
        )
    }
}

/**
 * 统一的手势 HUD 胶囊，自带 150ms 淡入淡出。
 * 显隐必须由 visible 传入（不要在调用处用 if 包一层），否则组件被直接移出组合，退出动画不会播放。
 * align 必须挂在 AnimatedVisibility 这一层：动画容器内部是 wrapContent，align 放内层会跑到左上角。
 */
@Composable
private fun BoxScope.GestureHudPill(
    visible: Boolean = true,
    alignment: Alignment = Alignment.TopCenter,
    topPadding: Dp = 24.dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = Modifier.align(alignment),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xCC000000),
            modifier = modifier.then(
                if (topPadding > 0.dp) {
                    Modifier.padding(top = insets.top + topPadding)
                } else {
                    Modifier
                }
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 播放器轻量操作提示胶囊（用于刷新、跳过片头片尾、选集提示等操作反馈）
 * 视觉与手势 HUD 胶囊统一：12dp 圆角 / 80% 纯黑 / 16×10 内边距 / Normal 字重。
 * 显隐、动画与定位仍由 SnackbarHost 托管，本组件只负责外观渲染。
 */
@Composable
fun PlayerToastPill(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCC000000),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * 格式化倍速文本（对齐 FongMi 的 SpeedSetting.format: "倍速 3.0x"）
 */
private fun formatPlaybackSpeed(speed: Float): String {
    val isSingleDecimal = Math.abs(speed * 10 - Math.round(speed * 10)) < 0.001f
    val formatted = if (isSingleDecimal) {
        String.format(Locale.US, "%.1f", speed)
    } else {
        String.format(Locale.US, "%.2f", speed)
    }
    return "倍速 ${formatted}x"
}

