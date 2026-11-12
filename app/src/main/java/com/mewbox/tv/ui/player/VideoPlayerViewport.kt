package com.mewbox.tv.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil3.compose.AsyncImage
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.PlayerTrack
import com.mewbox.tv.player.Players
import com.mewbox.tv.util.AppToast
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_DELAY_MS = 4500L
private const val GESTURE_SEEK_FULL_SWIPE_MS = 90_000L

/** 长按屏幕时的临时倍速 */
private const val LONG_PRESS_BOOST_SPEED = 3.0f

/** 手势亮度下限：避免把背光压到 0 导致整屏全黑 */
private const val MIN_GESTURE_BRIGHTNESS = 0.01f

/**
 * 读取当前系统亮度并归一化到 0..1，作为亮度手势的起步基准。
 *
 * 自动亮度开启时系统仍会把实时生效值写回 SCREEN_BRIGHTNESS，因此这里拿到的是用户当下
 * 真正看到的亮度，而不是写死的 0.5f —— 后者会让首次滑动的瞬间亮度跳一大截。
 */
private fun readSystemBrightness(context: Context): Float = try {
    val raw = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        128,
    )
    (raw.coerceIn(0, 255) / 255f).coerceIn(MIN_GESTURE_BRIGHTNESS, 1f)
} catch (e: Exception) {
    0.5f
}

/**
 * 把 Window 级 screenBrightness 还原成「跟随系统」。
 *
 * 这个 override 只要留在 LayoutParams 里，窗口每次重新获得焦点（切回前台、收起通知栏、
 * 解锁）都会被 WindowManager 重新 apply 一次，亮度会瞬间跳到上次手势设定的值。
 */
private fun resetWindowBrightness(activity: Activity?) {
    val window = activity?.window ?: return
    val lp = window.attributes
    if (lp.screenBrightness >= 0f) {
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }
}

/**
 * 完整视频播放视窗组件
 * 沉浸式视窗 + 手势调节 HUD + 控制栏 + 全屏操作集
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerViewport(
    vod: Vod,
    players: Players,
    playerState: PlayerState,
    currentEpisodeName: String,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    isResolvingPlay: Boolean,
    isAutoSearching: Boolean,
    autoSearchMessage: String?,
    onBack: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onRefreshPlayback: () -> Unit = {},
    onOpenEpisodeDrawer: () -> Unit,
    opening: Long = 0L,
    ending: Long = 0L,
    onSetOpening: () -> Unit = {},
    onResetOpening: () -> Unit = {},
    onSetEnding: () -> Unit = {},
    onResetEnding: () -> Unit = {},
    onSelectTrack: (PlayerTrack) -> Unit = { players.selectTrack(it) },
    onDisableTrack: (Int) -> Unit = { players.setTrackTypeDisabled(it, true) },
    onResetToDefault: (Int) -> Unit = { players.resetTrackTypeToDefault(it) },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 进入播放页默认隐藏所有操作栏与展示信息，单击唤起
    var showControls by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var activeTrackType by remember { mutableStateOf<Int?>(null) }

    var isSeekingByGesture by remember { mutableStateOf(false) }
    var gestureSeekTargetMs by remember { mutableLongStateOf(0L) }
    var gestureSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var initialSeekPosition by remember { mutableLongStateOf(0L) }
    var pendingGestureSeekTarget by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(playerState.currentPosition, pendingGestureSeekTarget) {
        val target = pendingGestureSeekTarget ?: return@LaunchedEffect
        if (kotlin.math.abs(playerState.currentPosition - target) <= 1500L) {
            pendingGestureSeekTarget = null
        }
    }

    LaunchedEffect(pendingGestureSeekTarget) {
        if (pendingGestureSeekTarget != null) {
            delay(3000L)
            pendingGestureSeekTarget = null
        }
    }

    // 亮度手势从「当前真实系统亮度」起步，避免首次滑动就跳到固定的 0.5
    val systemBrightness = remember(context) { readSystemBrightness(context) }
    var isBrightnessAdjusting by remember { mutableStateOf(false) }
    var currentBrightness by remember { mutableFloatStateOf(systemBrightness) }
    var initialBrightness by remember { mutableFloatStateOf(systemBrightness) }

    // screenBrightness 是 Window 级覆盖，只在「播放会话 + 前台」期间有效：
    // 切到后台立即释放，让其它 app 完全使用系统亮度，回到前台也不会被重新 apply 成旧值；
    // 退出播放页（组件销毁）时同样还原，否则首页 / 设置页会继承播放时调过的亮度。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> resetWindowBrightness(activity)
                Lifecycle.Event.ON_START -> {
                    // override 已释放，HUD 基准同步回真实系统亮度，避免下一次手势仍从旧值起步
                    val brightness = readSystemBrightness(context)
                    currentBrightness = brightness
                    initialBrightness = brightness
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resetWindowBrightness(activity)
        }
    }

    var isVolumeAdjusting by remember { mutableStateOf(false) }
    var currentVolumeRatio by remember { mutableFloatStateOf(0.5f) }
    var initialVolumeRatio by remember { mutableFloatStateOf(0.5f) }

    // 长按倍速播放：boost 期间显示提示，松手恢复按下前的倍速
    var isBoostPlaying by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }

    // 组件被回收或离开播放页面时，确保恢复倍速
    DisposableEffect(Unit) {
        onDispose {
            if (isBoostPlaying) {
                players.setPlaybackSpeed(speedBeforeBoost)
            }
        }
    }

    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnPlayPauseToggle by rememberUpdatedState(onPlayPauseToggle)
    val currentIsLocked by rememberUpdatedState(isLocked)
    val currentIsPlaying by rememberUpdatedState(playerState.isPlaying)
    val currentDuration by rememberUpdatedState(playerState.duration)

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }

    // 控制栏自动隐藏定时器（4.5秒）
    LaunchedEffect(showControls, isLocked) {
        if (showControls && !isLocked) {
            delay(CONTROLS_AUTO_HIDE_DELAY_MS)
            showControls = false
        }
    }

    // 全屏播放防息屏控制：当正在播放、缓冲或解析源时保持屏幕常亮；暂停或退出全屏时自动解除常亮
    val shouldKeepScreenOn = playerState.isPlaying ||
        playerState.playbackState == PlaybackState.BUFFERING ||
        isResolvingPlay ||
        isAutoSearching

    DisposableEffect(shouldKeepScreenOn) {
        val window = activity?.window
        if (shouldKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val isLandscape = maxWidth > maxHeight

        // 1. 底层 PlayerView（禁止使用 Media3 自带控制器，由上方 Compose 层统一绘制交互控制栏）
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    controllerAutoShow = false
                    player = players.exoPlayer
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true

                    // 配置字幕样式：白字 + 单字黑色描边 + 背景透明（对齐 MewPlayer，消除整块黑色背景）
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            )
                        )
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(false)
                        // 动态字号策略：横屏按高度 5.33% 自适应适配各类屏幕；竖屏使用固定 17sp 避免因高纵横比导致字幕被拉大两倍
                        if (isLandscape) {
                            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
                        } else {
                            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        }
                    }
                }
            },
            update = { pv ->
                pv.resizeMode = playerState.resizeMode
                pv.keepScreenOn = shouldKeepScreenOn
                pv.subtitleView?.let { subView ->
                    if (isLandscape) {
                        subView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
                    } else {
                        subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    }
                }
            },
            onRelease = { pv ->
                pv.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 2. 独立手势捕获层（位于视频和背景海报上方，但在控制栏、HUD 和对话框下方）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPlayerGestures(
                        isLocked = { currentIsLocked },
                        isPlaying = { currentIsPlaying },
                        onSingleTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = {
                            if (!currentIsLocked) currentOnPlayPauseToggle()
                        },
                        onLongPressStart = {
                            timber.log.Timber.d("viewport: onLongPressStart called! currentIsLocked=%s, currentIsPlaying=%s, players.isPlaying=%s", currentIsLocked, currentIsPlaying, players.state.value.isPlaying)
                            if (!currentIsLocked && (currentIsPlaying || players.state.value.isPlaying)) {
                                speedBeforeBoost = players.state.value.speed
                                isBoostPlaying = true
                                players.setPlaybackSpeed(LONG_PRESS_BOOST_SPEED)
                            }
                        },
                        onLongPressEnd = {
                            if (isBoostPlaying) {
                                isBoostPlaying = false
                                players.setPlaybackSpeed(speedBeforeBoost)
                            }
                        },
                        onSeekStart = {
                            isSeekingByGesture = true
                            val basePos = players.exoPlayer.currentPosition.coerceAtLeast(0L)
                            initialSeekPosition = basePos
                            gestureSeekDeltaMs = 0L
                            gestureSeekTargetMs = basePos
                        },
                        onSeekChange = { deltaRatioX ->
                            val dur = players.exoPlayer.duration.takeIf { it > 0 }
                                ?: currentDuration.takeIf { it > 0 }
                            val fullSwipeMs = dur?.let { (it / 15).coerceIn(90_000L, 300_000L) }
                                ?: GESTURE_SEEK_FULL_SWIPE_MS
                            val deltaMs = (deltaRatioX * fullSwipeMs).toLong()
                            gestureSeekDeltaMs = deltaMs
                            gestureSeekTargetMs = if (dur != null) {
                                (initialSeekPosition + deltaMs).coerceIn(0L, dur)
                            } else {
                                (initialSeekPosition + deltaMs).coerceAtLeast(0L)
                            }
                        },
                        onSeekEnd = {
                            if (isSeekingByGesture) {
                                val target = gestureSeekTargetMs
                                pendingGestureSeekTarget = target
                                currentOnSeekTo(target)
                                isSeekingByGesture = false
                                gestureSeekTargetMs = 0L
                                gestureSeekDeltaMs = 0L
                            }
                        },
                        onBrightnessStart = {
                            isBrightnessAdjusting = true
                            val window = activity?.window
                            val lp = window?.attributes
                            initialBrightness = if (lp != null && lp.screenBrightness >= 0f) lp.screenBrightness else currentBrightness
                        },
                        onBrightnessChange = { deltaRatioY ->
                            val window = activity?.window
                            val lp = window?.attributes
                            val target = (initialBrightness + deltaRatioY * 1.5f).coerceIn(MIN_GESTURE_BRIGHTNESS, 1f)
                            currentBrightness = target
                            lp?.screenBrightness = target
                            window?.attributes = lp
                        },
                        onBrightnessEnd = {
                            isBrightnessAdjusting = false
                        },
                        onVolumeStart = {
                            isVolumeAdjusting = true
                            if (audioManager != null && maxVolume > 0) {
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                initialVolumeRatio = currentVol.toFloat() / maxVolume
                            }
                        },
                        onVolumeChange = { deltaRatioY ->
                            if (audioManager != null && maxVolume > 0) {
                                val targetRatio = (initialVolumeRatio + deltaRatioY * 1.5f).coerceIn(0f, 1f)
                                val newVol = (targetRatio * maxVolume).toInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                currentVolumeRatio = targetRatio
                            }
                        },
                        onVolumeEnd = {
                            isVolumeAdjusting = false
                        },
                    )
                },
        )

        val isLoading = isResolvingPlay || isAutoSearching || playerState.playbackState == PlaybackState.BUFFERING
        val isError = playerState.playbackState == PlaybackState.ERROR

        // 2. 加载 / 缓冲 / 跨源检索中提示
        if (isLoading) {
            PlayerLoadingOverlay(
                isResolvingPlay = isResolvingPlay,
                isAutoSearching = isAutoSearching,
                autoSearchMessage = autoSearchMessage,
                networkSpeed = playerState.networkSpeed,
            )
        }

        // 3. 播放错误提示
        if (isError) {
            PlayerErrorOverlay(errorMessage = playerState.errorMessage)
        }

        // 4. 手势浮层 HUD（快进快退、亮度、音量、长按倍速）
        PlayerGestureHud(
            isSeekingByGesture = isSeekingByGesture,
            gestureSeekTargetMs = gestureSeekTargetMs,
            gestureSeekDeltaMs = gestureSeekDeltaMs,
            isBrightnessAdjusting = isBrightnessAdjusting,
            currentBrightness = currentBrightness,
            isVolumeAdjusting = isVolumeAdjusting,
            currentVolumeRatio = currentVolumeRatio,
            isBoostPlaying = isBoostPlaying,
            boostSpeed = LONG_PRESS_BOOST_SPEED,
        )

        // 5. 屏幕锁定时只显示解锁按钮
        if (isLocked) {
            PlayerLockedOverlay(
                visible = showControls,
                onUnlock = { isLocked = false },
            )
            return@BoxWithConstraints
        }

        // 6. 交互控制层（淡入淡出遮罩）
        PlayerControlsOverlay(
            visible = showControls,
            showCenterControls = !isLoading && !isError,
            vod = vod,
            playerState = playerState,
            isLandscape = isLandscape,
            currentEpisodeName = currentEpisodeName,
            hasPrevEpisode = hasPrevEpisode,
            hasNextEpisode = hasNextEpisode,
            onBack = onBack,
            onShowMediaInfo = { showInfoDialog = true },
            onPlayPauseToggle = onPlayPauseToggle,
            onSeekTo = onSeekTo,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onRefreshPlayback = onRefreshPlayback,
            onShowSubtitleTracks = { activeTrackType = C.TRACK_TYPE_TEXT },
            onShowAudioTracks = { activeTrackType = C.TRACK_TYPE_AUDIO },
            onShowVideoTracks = { activeTrackType = C.TRACK_TYPE_VIDEO },
            onLockScreen = { isLocked = true },
            onOpenEpisodeDrawer = onOpenEpisodeDrawer,
            opening = opening,
            ending = ending,
            onSetOpening = onSetOpening,
            onResetOpening = onResetOpening,
            onSetEnding = onSetEnding,
            onResetEnding = onResetEnding,
            externalSeekPosition = if (isSeekingByGesture) gestureSeekTargetMs else pendingGestureSeekTarget,
        )
    }

    if (showInfoDialog) {
        PlayerMediaInfoDialog(
            vodName = vod.vodName,
            currentEpisodeName = currentEpisodeName,
            headers = playerState.headers,
            playUrl = playerState.playUrl,
            onDismiss = { showInfoDialog = false },
        )
    }

    activeTrackType?.let { type ->
        val tracks = when (type) {
            C.TRACK_TYPE_TEXT -> playerState.subtitleTracks
            C.TRACK_TYPE_AUDIO -> playerState.audioTracks
            C.TRACK_TYPE_VIDEO -> playerState.videoTracks
            else -> emptyList()
        }
        TrackSelectionDialog(
            type = type,
            tracks = tracks,
            isTypeDisabled = if (type == C.TRACK_TYPE_TEXT) playerState.isSubtitleDisabled else false,
            onSelectTrack = { track ->
                if (!track.isSupported) {
                    AppToast.show("当前设备暂不支持该音轨解码 (${track.name})")
                    return@TrackSelectionDialog
                }
                onSelectTrack(track)
                AppToast.show("已切换：${track.name}")
            },
            onDisableTrack = {
                onDisableTrack(type)
                AppToast.show("已关闭字幕")
            },
            onResetToDefault = {
                onResetToDefault(type)
                AppToast.show("已恢复系统自适应")
            },
            onDismiss = { activeTrackType = null },
        )
    }
}
