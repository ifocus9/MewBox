package com.mewbox.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.Players

private val QuickActionButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)

/**
 * 屏幕锁定时的单解锁按钮浮层
 */
@Composable
internal fun BoxScope.PlayerLockedOverlay(
    visible: Boolean,
    onUnlock: () -> Unit,
) {
    val insets = rememberPlayerSafeInsets()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = insets.horizontal + 16.dp),
    ) {
        IconButton(
            onClick = onUnlock,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "解锁屏幕",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 播放器主控制层（顶部栏、中心播放键、右侧锁屏键、底部进度条与快捷工具栏）
 */
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    showCenterControls: Boolean,
    vod: Vod,
    playerState: PlayerState,
    currentEpisodeName: String,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    onBack: () -> Unit,
    onShowMediaInfo: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onRefreshPlayback: () -> Unit = {},
    onShowSubtitleTracks: () -> Unit = {},
    onShowAudioTracks: () -> Unit = {},
    onShowVideoTracks: () -> Unit = {},
    onLockScreen: () -> Unit,
    onOpenEpisodeDrawer: () -> Unit,
    opening: Long = 0L,
    ending: Long = 0L,
    onSetOpening: () -> Unit = {},
    onResetOpening: () -> Unit = {},
    onSetEnding: () -> Unit = {},
    onResetEnding: () -> Unit = {},
    externalSeekPosition: Long? = null,
) {
    val insets = rememberPlayerSafeInsets()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x99000000),
                            Color.Transparent,
                            Color(0xBB000000),
                        )
                    )
                ),
        ) {
            // A. 顶部栏 (Top Bar)
            PlayerTopBar(
                vodName = vod.vodName,
                currentEpisodeName = currentEpisodeName,
                videoWidth = playerState.videoWidth,
                videoHeight = playerState.videoHeight,
                onBack = onBack,
                onShowMediaInfo = onShowMediaInfo,
                insets = insets,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // B. 中心控制区 (上一集、播放/暂停、下一集)，加载或报错时隐藏避免与中心状态浮层重叠
            if (showCenterControls) {
                PlayerCenterControls(
                    isPlaying = playerState.isPlaying,
                    hasPrevEpisode = hasPrevEpisode,
                    hasNextEpisode = hasNextEpisode,
                    onPlayPrevious = onPlayPrevious,
                    onPlayPauseToggle = onPlayPauseToggle,
                    onPlayNext = onPlayNext,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // C. 右侧悬浮控制栏（屏幕锁）
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = insets.horizontal + 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(
                    onClick = onLockScreen,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = "锁定屏幕",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // D. 底部控制栏（进度条、时间、快捷操作工具条）
            PlayerBottomBar(
                duration = playerState.duration,
                currentPosition = playerState.currentPosition,
                bufferedPosition = playerState.bufferedPosition,
                externalSeekPosition = externalSeekPosition,
                onSeekTo = onSeekTo,
                onRefreshPlayback = onRefreshPlayback,
                onShowSubtitleTracks = onShowSubtitleTracks,
                onShowAudioTracks = onShowAudioTracks,
                onShowVideoTracks = onShowVideoTracks,
                onOpenEpisodeDrawer = onOpenEpisodeDrawer,
                opening = opening,
                ending = ending,
                onSetOpening = onSetOpening,
                onResetOpening = onResetOpening,
                onSetEnding = onSetEnding,
                onResetEnding = onResetEnding,
                insets = insets,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    vodName: String,
    currentEpisodeName: String,
    videoWidth: Int,
    videoHeight: Int,
    onBack: () -> Unit,
    onShowMediaInfo: () -> Unit,
    insets: PlayerSafeInsets,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = insets.horizontal + 8.dp,
                    end = insets.horizontal + 8.dp,
                    top = insets.top + 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val titleText = if (currentEpisodeName.isNotEmpty()) {
                    "$vodName · $currentEpisodeName"
                } else {
                    vodName
                }
                Text(
                    text = titleText,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (videoWidth > 0 && videoHeight > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$videoWidth x $videoHeight",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        maxLines = 1,
                    )
                }
            }

            IconButton(
                onClick = onShowMediaInfo,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "媒体信息",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            PlayerBatteryTimeIndicator(
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    onPlayPrevious: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        IconButton(
            onClick = onPlayPrevious,
            enabled = hasPrevEpisode,
            modifier = Modifier
                .size(48.dp)
                .background(if (hasPrevEpisode) Color(0x55000000) else Color(0x22000000), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一集",
                tint = if (hasPrevEpisode) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(26.dp),
            )
        }

        IconButton(
            onClick = onPlayPauseToggle,
            modifier = Modifier
                .size(64.dp)
                .background(Color(0x77000000), CircleShape),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "播放/暂停",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }

        IconButton(
            onClick = onPlayNext,
            enabled = hasNextEpisode,
            modifier = Modifier
                .size(48.dp)
                .background(if (hasNextEpisode) Color(0x55000000) else Color(0x22000000), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一集",
                tint = if (hasNextEpisode) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun PlayerBottomBar(
    duration: Long,
    currentPosition: Long,
    bufferedPosition: Long,
    externalSeekPosition: Long?,
    onSeekTo: (Long) -> Unit,
    onRefreshPlayback: () -> Unit = {},
    onShowSubtitleTracks: () -> Unit = {},
    onShowAudioTracks: () -> Unit = {},
    onShowVideoTracks: () -> Unit = {},
    onOpenEpisodeDrawer: () -> Unit,
    opening: Long = 0L,
    ending: Long = 0L,
    onSetOpening: () -> Unit = {},
    onResetOpening: () -> Unit = {},
    onSetEnding: () -> Unit = {},
    onResetEnding: () -> Unit = {},
    insets: PlayerSafeInsets,
    modifier: Modifier = Modifier,
) {
    var userDraggingProgress by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var pendingSeekTarget by remember { mutableStateOf<Long?>(null) }

    val currentDuration by rememberUpdatedState(duration)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)

    LaunchedEffect(currentPosition, pendingSeekTarget) {
        val target = pendingSeekTarget ?: return@LaunchedEffect
        if (kotlin.math.abs(currentPosition - target) <= 1500L) {
            pendingSeekTarget = null
        }
    }

    LaunchedEffect(pendingSeekTarget) {
        if (pendingSeekTarget != null) {
            delay(3000L)
            pendingSeekTarget = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = insets.horizontal + 14.dp,
                end = insets.horizontal + 14.dp,
                top = 6.dp,
                bottom = insets.bottom + 6.dp,
            ),
    ) {
        // 进度条行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayedPosition = when {
                userDraggingProgress -> (sliderPosition * currentDuration.coerceAtLeast(0L)).toLong()
                externalSeekPosition != null -> externalSeekPosition
                pendingSeekTarget != null -> pendingSeekTarget!!
                else -> currentPosition
            }
            Text(
                text = Players.formatDuration(displayedPosition),
                color = Color.White,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )

            Spacer(modifier = Modifier.width(12.dp))

            val progressRatio = when {
                userDraggingProgress -> sliderPosition
                externalSeekPosition != null && currentDuration > 0 -> (externalSeekPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
                pendingSeekTarget != null && currentDuration > 0 -> (pendingSeekTarget!!.toFloat() / currentDuration).coerceIn(0f, 1f)
                currentDuration > 0 -> (currentPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
                else -> 0f
            }
            val bufferedRatio = if (currentDuration > 0) {
                (bufferedPosition.toFloat() / currentDuration).coerceIn(0f, 1f)
            } else {
                0f
            }

            VideoPlayerProgressBar(
                progress = progressRatio,
                bufferedProgress = bufferedRatio,
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
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = Players.formatDuration(currentDuration),
                color = Color.White,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        // 底部快捷操作工具栏
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerQuickActionButton(text = "刷新", onClick = onRefreshPlayback)
                PlayerQuickActionButton(text = "字幕", onClick = onShowSubtitleTracks)
                PlayerQuickActionButton(text = "音轨", onClick = onShowAudioTracks)
                PlayerQuickActionButton(text = "视轨", onClick = onShowVideoTracks)

                Spacer(modifier = Modifier.weight(1f))

                val openingText = if (opening <= 0L) "片头" else Players.formatDuration(opening)
                val endingText = if (ending <= 0L) "片尾" else Players.formatDuration(ending)

                PlayerQuickActionButton(
                    text = openingText,
                    onClick = onSetOpening,
                    onLongClick = onResetOpening,
                )
                PlayerQuickActionButton(
                    text = endingText,
                    onClick = onSetEnding,
                    onLongClick = onResetEnding,
                )
                PlayerQuickActionButton(
                    text = "选集",
                    onClick = onOpenEpisodeDrawer,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerQuickActionButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(QuickActionButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = TextStyle(
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

