package com.mewbox.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.player.Players
import com.mewbox.tv.ui.theme.MewBoxTheme
import kotlin.math.roundToInt

/**
 * 播放器轻量缓冲 / 加载提示浮层
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
                trackColor = Color.White.copy(alpha = 0.14f),
                strokeCap = StrokeCap.Round,
            )
            if (displayText.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = displayText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
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
 * 播放错误提示浮层（带直达重试与切换线路胶囊按钮）
 */
@Composable
internal fun PlayerErrorOverlay(
    errorMessage: String?,
    onRetry: () -> Unit,
    onSwitchLine: (() -> Unit)? = null,
) {
    val accent = MewBoxTheme.colors.panelAccent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .padding(horizontal = 24.dp)
                .playerFlatSurface(shape = RoundedCornerShape(16.dp), baseAlpha = 0.82f)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "播放异常",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorMessage ?: "解析或播放直链失败",
                color = Color.White.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.28f))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "重新加载",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (onSwitchLine != null) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(onClick = onSwitchLine)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "切换线路",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
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
    val accent = MewBoxTheme.colors.panelAccent

    // 长按倍速播放提示
    GestureHudPill(visible = isBoostPlaying) {
        val pulseTransition = rememberInfiniteTransition(label = "boostPulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 550),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "boostIconAlpha",
        )
        Icon(
            imageVector = Icons.Rounded.FastForward,
            contentDescription = null,
            tint = Color.White.copy(alpha = pulseAlpha),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${formatSpeedLabel(boostSpeed)} 倍速中",
            color = Color.White,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
        )
    }

    // 水平滑屏快进 / 快退提示
    GestureHudPill(visible = isSeekingByGesture) {
        Icon(
            imageVector = if (gestureSeekDeltaMs >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        val sign = if (gestureSeekDeltaMs >= 0) "+" else ""
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                    append(Players.formatDuration(gestureSeekTargetMs))
                }
                append("  ")
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
                    append("$sign${gestureSeekDeltaMs / 1000}s")
                }
            },
            style = TextStyle(
                fontSize = 13.sp,
                fontFeatureSettings = "tnum",
            ),
        )
    }

    // 亮度调节 HUD
    GestureHudPill(visible = isBrightnessAdjusting) {
        val brightnessIcon = when {
            currentBrightness > 0.66f -> Icons.Rounded.BrightnessHigh
            currentBrightness > 0.33f -> Icons.Rounded.BrightnessMedium
            else -> Icons.Rounded.BrightnessLow
        }
        Icon(
            imageVector = brightnessIcon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { currentBrightness },
            modifier = Modifier
                .width(96.dp)
                .height(4.dp)
                .clip(CircleShape),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.28f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(currentBrightness * 100f).roundToInt().coerceIn(0, 100)}%",
            color = Color.White.copy(alpha = 0.9f),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
        )
    }

    // 音量调节 HUD
    GestureHudPill(visible = isVolumeAdjusting) {
        val volumeIcon = when {
            currentVolumeRatio <= 0.05f -> Icons.AutoMirrored.Rounded.VolumeOff
            currentVolumeRatio > 0.5f -> Icons.AutoMirrored.Rounded.VolumeUp
            else -> Icons.AutoMirrored.Rounded.VolumeDown
        }
        Icon(
            imageVector = volumeIcon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { currentVolumeRatio },
            modifier = Modifier
                .width(96.dp)
                .height(4.dp)
                .clip(CircleShape),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.28f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(currentVolumeRatio * 100f).roundToInt().coerceIn(0, 100)}%",
            color = Color.White.copy(alpha = 0.9f),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/**
 * 统一手势 HUD 圆角胶囊（带暗色微玻璃质感和 150ms 淡入淡出）
 */
@Composable
private fun BoxScope.GestureHudPill(
    visible: Boolean = true,
    alignment: Alignment = Alignment.TopCenter,
    topPadding: Dp = 22.dp,
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
        Row(
            modifier = modifier
                .then(
                    if (topPadding > 0.dp) {
                        Modifier.padding(top = insets.top + topPadding)
                    } else {
                        Modifier
                    },
                )
                .playerFlatSurface(shape = CircleShape, baseAlpha = 0.62f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 播放器轻量操作提示胶囊（供 SnackbarHost 宿主渲染）
 */
@Composable
fun PlayerToastPill(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .playerFlatSurface(shape = CircleShape, baseAlpha = 0.66f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Color.White,
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
