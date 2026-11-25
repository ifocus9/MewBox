package com.mewbox.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.Players
import com.mewbox.tv.player.TrafficSpeedMeter
import com.mewbox.tv.ui.theme.MewBoxTheme
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 屏幕锁定时的解锁按钮浮层（左侧居中，无背景纯图标）
 */
@Composable
internal fun BoxScope.PlayerLockedOverlay(
    visible: Boolean,
    onUnlock: () -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)) +
            scaleIn(tween(PLAYER_CONTROLS_ENTER_MS), initialScale = 0.88f),
        exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)) +
            scaleOut(tween(PLAYER_CONTROLS_EXIT_MS), targetScale = 0.88f),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = insets.horizontal + 16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onUnlock),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "解锁屏幕",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 双指缩放偏离 1.0x 时的右侧还原胶囊
 */
@Composable
internal fun BoxScope.PlayerZoomResetPill(
    visible: Boolean,
    zoomScale: Float,
    onResetZoom: () -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)) +
            scaleIn(tween(PLAYER_CONTROLS_ENTER_MS), initialScale = 0.9f),
        exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)) +
            scaleOut(tween(PLAYER_CONTROLS_EXIT_MS), targetScale = 0.9f),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = insets.horizontal + 16.dp),
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .playerFlatSurface(shape = CircleShape, baseAlpha = 0.58f)
                .clickable(onClick = onResetZoom)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FitScreen,
                contentDescription = "还原缩放",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "${formatZoomLabel(zoomScale)} · 还原",
                color = Color.White,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

/**
 * 现代影院级播放器主控制层：
 * - 画面中心完全留白零遮挡
 * - 顶栏：返回键 + 标题 + 精简画质/倍速短徽章 + 单行横排系统时间电量
 * - 左侧居中：锁屏按钮
 * - 底栏：信息行 + 全宽进度条(含片头尾刻度点) + 左侧高频播控 & 右侧精简胶囊入口
 * - 空间分向动效：顶栏自上滑入、底栏自下滑入、侧栏微缩弹入
 */
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    showNetworkSpeed: Boolean,
    vod: Vod,
    playerState: PlayerState,
    isLandscape: Boolean,
    currentEpisodeName: String,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    hasMultiQuality: Boolean,
    currentQualityName: String,
    opening: Long,
    ending: Long,
    externalSeekPosition: Long?,
    onBack: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onLockScreen: () -> Unit,
    onOpenSpeedDrawer: () -> Unit,
    onOpenQualityDrawer: () -> Unit,
    onOpenTrackDrawer: () -> Unit,
    onOpenEpisodeDrawer: () -> Unit,
    onOpenMoreDrawer: () -> Unit,
    onProgressDraggingChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    val showEpisodeControls = hasPrevEpisode || hasNextEpisode
    val hasMultiFlags = vod.playFlags.size > 1

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部与底部渐隐 Scrim
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)),
            exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(108.dp + insets.top)
                        .background(PlayerTopScrimBrush),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(120.dp + insets.bottom)
                        .background(PlayerBottomScrimBrush),
                )
            }
        }

        // A. 顶部栏（自上滑入）
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)) +
                slideInVertically(tween(PLAYER_CONTROLS_ENTER_MS)) { -28 },
            exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)) +
                slideOutVertically(tween(PLAYER_CONTROLS_EXIT_MS)) { -28 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                vodName = vod.vodName,
                currentEpisodeName = currentEpisodeName,
                videoWidth = playerState.videoWidth,
                videoHeight = playerState.videoHeight,
                insets = insets,
                onBack = onBack,
            )
        }

        // B. 左侧悬浮锁屏按钮（微缩弹入，无背景纯图标）
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)) +
                scaleIn(tween(PLAYER_CONTROLS_ENTER_MS), initialScale = 0.88f),
            exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)) +
                scaleOut(tween(PLAYER_CONTROLS_EXIT_MS), targetScale = 0.88f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = insets.horizontal + 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable {
                        onInteraction()
                        onLockScreen()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = "锁定屏幕",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // C. 底部控制区（自下滑入）
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(PLAYER_CONTROLS_ENTER_MS)) +
                slideInVertically(tween(PLAYER_CONTROLS_ENTER_MS)) { 36 },
            exit = fadeOut(tween(PLAYER_CONTROLS_EXIT_MS)) +
                slideOutVertically(tween(PLAYER_CONTROLS_EXIT_MS)) { 36 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(
                duration = playerState.duration,
                currentPosition = playerState.currentPosition,
                bufferedPosition = playerState.bufferedPosition,
                isPlaying = playerState.isPlaying,
                speed = playerState.speed,
                showNetworkSpeed = showNetworkSpeed,
                isLandscape = isLandscape,
                showEpisodeControls = showEpisodeControls,
                hasPrevEpisode = hasPrevEpisode,
                hasNextEpisode = hasNextEpisode,
                hasMultiFlags = hasMultiFlags,
                hasMultiQuality = hasMultiQuality,
                currentQualityName = currentQualityName,
                opening = opening,
                ending = ending,
                externalSeekPosition = externalSeekPosition,
                insets = insets,
                onSeekTo = onSeekTo,
                onSeekBy = { delta ->
                    onInteraction()
                    onSeekBy(delta)
                },
                onPlayPauseToggle = {
                    onInteraction()
                    onPlayPauseToggle()
                },
                onPlayPrevious = {
                    onInteraction()
                    onPlayPrevious()
                },
                onPlayNext = {
                    onInteraction()
                    onPlayNext()
                },
                onOpenSpeedDrawer = {
                    onInteraction()
                    onOpenSpeedDrawer()
                },
                onOpenQualityDrawer = {
                    onInteraction()
                    onOpenQualityDrawer()
                },
                onOpenTrackDrawer = {
                    onInteraction()
                    onOpenTrackDrawer()
                },
                onOpenEpisodeDrawer = {
                    onInteraction()
                    onOpenEpisodeDrawer()
                },
                onOpenMoreDrawer = {
                    onInteraction()
                    onOpenMoreDrawer()
                },
                onProgressDraggingChanged = onProgressDraggingChanged,
            )
        }
    }
}

/**
 * 顶部导航与系统状态栏（横竖屏统一保留返回键 + 标题 + 精简短徽章 + 单行横排时间电量）
 */
@Composable
private fun PlayerTopBar(
    vodName: String,
    currentEpisodeName: String,
    videoWidth: Int,
    videoHeight: Int,
    insets: PlayerSafeInsets,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolutionBadge = remember(videoWidth, videoHeight) {
        formatResolutionBadge(videoWidth, videoHeight)
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = insets.horizontal + 10.dp,
                    end = insets.horizontal + 14.dp,
                    top = insets.top + 8.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val titleText = if (currentEpisodeName.isNotBlank()) {
                    "$vodName · $currentEpisodeName"
                } else {
                    vodName
                }
                Text(
                    text = titleText,
                    color = Color.White,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (resolutionBadge != null) {
                    TopBarBadge(
                        text = resolutionBadge,
                        bgColor = Color.White.copy(alpha = 0.16f),
                        textColor = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            PlayerBatteryTimeIndicator()
        }
    }
}

@Composable
private fun TopBarBadge(
    text: String,
    bgColor: Color,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
        )
    }
}

/**
 * 底部双段语义控制栏：
 * 1. 信息行（当前/总时长 + 实时下行网速）
 * 2. 满宽进度条
 * 3. 左端核心播控 + 右端轻量胶囊功能键
 */
@Composable
private fun PlayerBottomBar(
    duration: Long,
    currentPosition: Long,
    bufferedPosition: Long,
    isPlaying: Boolean,
    speed: Float,
    showNetworkSpeed: Boolean,
    isLandscape: Boolean,
    showEpisodeControls: Boolean,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    hasMultiFlags: Boolean,
    hasMultiQuality: Boolean,
    currentQualityName: String,
    opening: Long,
    ending: Long,
    externalSeekPosition: Long?,
    insets: PlayerSafeInsets,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onOpenSpeedDrawer: () -> Unit,
    onOpenQualityDrawer: () -> Unit,
    onOpenTrackDrawer: () -> Unit,
    onOpenEpisodeDrawer: () -> Unit,
    onOpenMoreDrawer: () -> Unit,
    onProgressDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var userDraggingProgress by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var pendingSeekTarget by remember { mutableStateOf<Long?>(null) }

    val currentDuration by rememberUpdatedState(duration)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    var liveNetworkSpeed by remember { mutableStateOf("") }

    LaunchedEffect(showNetworkSpeed) {
        if (!showNetworkSpeed) {
            liveNetworkSpeed = ""
            return@LaunchedEffect
        }
        val meter = TrafficSpeedMeter()
        withContext(Dispatchers.IO) {
            meter.getSpeed()
            delay(300L)
            while (isActive) {
                val speedText = meter.getSpeed().orEmpty()
                withContext(Dispatchers.Main) {
                    liveNetworkSpeed = speedText
                }
                delay(1000L)
            }
        }
    }

    LaunchedEffect(currentPosition, pendingSeekTarget) {
        val target = pendingSeekTarget ?: return@LaunchedEffect
        if (abs(currentPosition - target) <= 1500L) {
            pendingSeekTarget = null
        }
    }

    LaunchedEffect(pendingSeekTarget) {
        if (pendingSeekTarget != null) {
            delay(3000L)
            pendingSeekTarget = null
        }
    }

    val safePendingTarget = pendingSeekTarget
    val displayedPosition = when {
        userDraggingProgress -> (sliderPosition * currentDuration.coerceAtLeast(0L)).toLong()
        externalSeekPosition != null -> externalSeekPosition
        safePendingTarget != null -> safePendingTarget
        else -> currentPosition
    }
    val progressRatio = when {
        userDraggingProgress -> sliderPosition
        externalSeekPosition != null && currentDuration > 0 ->
            (externalSeekPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
        safePendingTarget != null && currentDuration > 0 ->
            (safePendingTarget.toFloat() / currentDuration).coerceIn(0f, 1f)
        currentDuration > 0 -> (currentPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
        else -> 0f
    }
    val bufferedRatio = if (currentDuration > 0) {
        (bufferedPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val openingRatio = if (opening > 0L && currentDuration > 0L) {
        (opening.toFloat() / currentDuration).coerceIn(0f, 1f)
    } else {
        null
    }
    val endingRatio = if (ending > 0L && currentDuration > 0L) {
        ((currentDuration - ending).coerceAtLeast(0L).toFloat() / currentDuration).coerceIn(0f, 1f)
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = insets.horizontal + 12.dp,
                end = insets.horizontal + 12.dp,
                bottom = insets.bottom,
            ),
    ) {
        // 第 1 行：时间进度读数 + 当前实时网速
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) {
                        append(Players.formatDuration(displayedPosition))
                    }
                    withStyle(SpanStyle(color = Color.White.copy(alpha = 0.62f))) {
                        append(" / ")
                        append(Players.formatDuration(currentDuration))
                    }
                },
                style = TextStyle(
                    fontSize = 12.5.sp,
                    fontFeatureSettings = "tnum",
                ),
            )

            Spacer(modifier = Modifier.weight(1f))

            if (showNetworkSpeed && liveNetworkSpeed.isNotBlank()) {
                Text(
                    text = liveNetworkSpeed,
                    color = Color.White.copy(alpha = 0.78f),
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                )
            }
        }

        // 第 2 行：全宽进度条
        VideoPlayerProgressBar(
            progress = progressRatio,
            bufferedProgress = bufferedRatio,
            openingRatio = openingRatio,
            endingRatio = endingRatio,
            onDraggingChanged = onProgressDraggingChanged,
            onSeek = { p ->
                userDraggingProgress = true
                sliderPosition = p
            },
            onSeekStart = {
                userDraggingProgress = true
            },
            onSeekFinished = { p ->
                userDraggingProgress = false
                val curDur = currentDuration.coerceAtLeast(0L)
                if (curDur > 0L) {
                    val targetMs = (p * curDur).toLong()
                    pendingSeekTarget = targetMs
                    currentOnSeekTo(targetMs)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 第 3 行：左侧核心播控 + 右侧胶囊操作入口
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧核心播控
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isLandscape) {
                        if (showEpisodeControls) {
                            TransportIconButton(
                                icon = Icons.Rounded.SkipPrevious,
                                contentDescription = "上一集",
                                enabled = hasPrevEpisode,
                                onClick = onPlayPrevious,
                            )
                            TransportIconButton(
                                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                iconSize = 28.dp,
                                onClick = onPlayPauseToggle,
                            )
                            TransportIconButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = "下一集",
                                enabled = hasNextEpisode,
                                onClick = onPlayNext,
                            )
                        } else {
                            TransportIconButton(
                                icon = Icons.Rounded.Replay10,
                                contentDescription = "快退10秒",
                                onClick = { onSeekBy(-10_000L) },
                            )
                            TransportIconButton(
                                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                iconSize = 28.dp,
                                onClick = onPlayPauseToggle,
                            )
                            TransportIconButton(
                                icon = Icons.Rounded.Forward10,
                                contentDescription = "快进10秒",
                                onClick = { onSeekBy(10_000L) },
                            )
                        }
                    } else {
                        TransportIconButton(
                            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            iconSize = 28.dp,
                            onClick = onPlayPauseToggle,
                        )
                        if (showEpisodeControls) {
                            TransportIconButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = "下一集",
                                enabled = hasNextEpisode,
                                onClick = onPlayNext,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 右侧操作组：状态/内容胶囊 + 末尾圆形工具图标
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val hasCustomSpeed = abs(speed - 1.0f) >= 0.02f
                    BottomCapsuleButton(
                        text = formatSpeedLabel(speed),
                        isHighlighted = hasCustomSpeed,
                        onClick = onOpenSpeedDrawer,
                    )

                    if (hasMultiQuality) {
                        BottomCapsuleButton(
                            text = currentQualityName,
                            onClick = onOpenQualityDrawer,
                        )
                    }

                    if (showEpisodeControls || hasMultiFlags) {
                        BottomCapsuleButton(
                            text = if (showEpisodeControls) "选集" else "线路",
                            onClick = onOpenEpisodeDrawer,
                        )
                    }

                    if (isLandscape) {
                        BottomCircleIconButton(
                            icon = Icons.Rounded.Subtitles,
                            contentDescription = "轨道设置",
                            onClick = onOpenTrackDrawer,
                        )
                    }

                    BottomCircleIconButton(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = "更多设置",
                        onClick = onOpenMoreDrawer,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun BottomCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun BottomCapsuleButton(
    text: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = MewBoxTheme.colors.panelAccent
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHighlighted) accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isHighlighted) accent else Color.White,
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
        )
    }
}
