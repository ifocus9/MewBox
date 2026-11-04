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
import androidx.compose.foundation.shape.CircleShape
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
        isAutoSearching -> autoSearchMessage ?: "正在跨源检索可用片源..."
        isResolvingPlay -> "正在解析播放直链..."
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
                modifier = Modifier.size(44.dp),
                color = Color.White,
                strokeWidth = 3.5.dp,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round,
            )
            if (displayText.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = displayText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.65f),
                            offset = Offset(0f, 1f),
                            blurRadius = 3f,
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
                    text = errorMessage ?: "解析或播放直链失败，请尝试换源",
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
    // 长按倍速播放提示：参考 FongMi (view_widget_vod 的 message + shape_widget)
    // 居于顶部中央（TopCenter），避让画面核心视觉区，采用 50% 半透明黑胶囊与极简文本
    AnimatedVisibility(
        visible = isBoostPlaying,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        val insets = rememberPlayerSafeInsets()
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0x80000000), // 对齐 FongMi 的 @color/black_50 (50% 半透明黑)
            modifier = Modifier.padding(top = insets.top + 24.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPlaybackSpeed(boostSpeed),
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }

    if (isSeekingByGesture) {
        GestureHudPill {
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }

    if (isBrightnessAdjusting) {
        GestureHudPill {
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
    }

    if (isVolumeAdjusting) {
        GestureHudPill {
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
}

@Composable
private fun BoxScope.GestureHudPill(
    alignment: Alignment = Alignment.TopCenter,
    topPadding: Dp = 48.dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCC000000),
        modifier = modifier
            .align(alignment)
            .then(
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

/**
 * 播放器轻量操作提示胶囊（用于刷新、跳过片头片尾、选集提示等操作反馈）
 * 居中微悬浮于控制条上方，半透明深色玻璃质感胶囊，彻底取代粗笨的全宽浅色卡片
 */
@Composable
fun PlayerToastPill(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xEB1E1E1E),
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = TextStyle(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
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

