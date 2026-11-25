package com.mewbox.tv.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.PlayerTrack
import com.mewbox.tv.player.Players
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val CONTROLS_AUTO_HIDE_DELAY_MS = 4500L
private const val ZOOM_HINT_HIDE_DELAY_MS = 2500L
private const val GESTURE_SEEK_FULL_SWIPE_MS = 90_000L

/** 长按屏幕时的临时倍速 */
private const val LONG_PRESS_BOOST_SPEED = 3.0f

/** 双指缩放倍率范围：以画面中心为锚点缩放，1x 为原始画面；支持小幅缩小以避让超宽画幅的屏幕圆角/挖孔 */
private const val DEFAULT_ZOOM_SCALE = 1f
private const val MIN_ZOOM_SCALE = 0.75f
private const val MAX_ZOOM_SCALE = 4f

/** 字幕下移的安全边界：字幕文字底边最多到「屏幕底部 − 此值」，超过就不再继续下移 */
private const val SUBTITLE_BOTTOM_SAFE_MARGIN_DP = 12f

/** Media3 SubtitleView 默认底部内边距 0.08f（javap 验证），文字底边约在 View 高度的 92% 处 */
private const val SUBTITLE_TEXT_BOTTOM_FRACTION = 0.92f

/** 手势亮度下限：避免把背光压到 0 导致整屏全黑 */
private const val MIN_GESTURE_BRIGHTNESS = 0.01f

private enum class PlayerSheet {
    NONE,
    SPEED,
    QUALITY,
    TRACKS,
    MORE,
}

private fun readSystemBrightness(context: Context): Float = try {
    val raw = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        128,
    )
    (raw.coerceIn(0, 255) / 255f).coerceIn(MIN_GESTURE_BRIGHTNESS, 1f)
} catch (e: Exception) {
    Timber.w(e, "读取系统亮度失败，回退默认 0.5")
    0.5f
}

private fun resetWindowBrightness(activity: Activity?) {
    val window = activity?.window ?: return
    val lp = window.attributes
    if (lp.screenBrightness >= 0f) {
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }
}

/**
 * 完整视频播放视窗组件：
 * 沉浸式视窗 + 手势调节 HUD + 影院级零遮挡控制栏 + 自适应悬浮操作面板
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
    playUrls: List<PlayUrl> = emptyList(),
    selectedPlayUrl: PlayUrl? = null,
    onSelectPlayUrl: (PlayUrl) -> Unit = {},
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
    val scope = rememberCoroutineScope()

    var showControls by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var isProgressBarDragging by remember { mutableStateOf(false) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }

    var activeSheet by remember { mutableStateOf(PlayerSheet.NONE) }
    var lastActiveSheet by remember { mutableStateOf(PlayerSheet.MORE) }

    fun openSheet(sheet: PlayerSheet) {
        lastActiveSheet = sheet
        activeSheet = sheet
        showControls = false
    }

    val hasMultiQuality = playUrls.size > 1 || (playUrls.size == 1 && playUrls.first().name != "默认")
    val currentQualityName = selectedPlayUrl?.name?.takeIf { it.isNotBlank() && it != "默认" } ?: "画质"
    val showEpisodeControls = hasPrevEpisode || hasNextEpisode
    val hasMultiFlags = vod.playFlags.size > 1

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

    val systemBrightness = remember(context) { readSystemBrightness(context) }
    var isBrightnessAdjusting by remember { mutableStateOf(false) }
    var currentBrightness by remember { mutableFloatStateOf(systemBrightness) }
    var initialBrightness by remember { mutableFloatStateOf(systemBrightness) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> resetWindowBrightness(activity)
                Lifecycle.Event.ON_START -> {
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

    var isBoostPlaying by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }

    var zoomScale by remember { mutableFloatStateOf(DEFAULT_ZOOM_SCALE) }
    var zoomGestureBase by remember { mutableFloatStateOf(DEFAULT_ZOOM_SCALE) }
    var zoomInteractionTick by remember { mutableIntStateOf(0) }
    var showZoomHintAfterPinch by remember { mutableStateOf(false) }
    var zoomResetJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(playerState.playUrl, currentEpisodeName) {
        zoomResetJob?.cancel()
        zoomScale = DEFAULT_ZOOM_SCALE
        zoomGestureBase = DEFAULT_ZOOM_SCALE
        showZoomHintAfterPinch = false
    }

    LaunchedEffect(zoomInteractionTick) {
        if (zoomInteractionTick > 0 && isZoomModified(zoomScale)) {
            showZoomHintAfterPinch = true
            delay(ZOOM_HINT_HIDE_DELAY_MS)
            showZoomHintAfterPinch = false
        }
    }

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

    // 控制栏与锁屏按钮自动隐藏：拖拽进度条或有交互动作时暂停/重置倒计时
    LaunchedEffect(showControls, isLocked, isProgressBarDragging, controlsInteractionTick, activeSheet) {
        if (!showControls || isProgressBarDragging || activeSheet != PlayerSheet.NONE) {
            return@LaunchedEffect
        }
        delay(CONTROLS_AUTO_HIDE_DELAY_MS)
        showControls = false
    }

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

    var viewportIsLandscape by remember { mutableStateOf(true) }

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val isLandscape = maxWidth > maxHeight
        viewportIsLandscape = isLandscape
        val insets = rememberPlayerSafeInsets()

        // 1. 底层 PlayerView
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

                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            ),
                        )
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(false)
                        if (isLandscape) {
                            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
                        } else {
                            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        }
                    }
                }
            },
            update = { pv ->
                pv.scaleX = zoomScale
                pv.scaleY = zoomScale
                pv.resizeMode = playerState.resizeMode
                pv.keepScreenOn = shouldKeepScreenOn
                pv.subtitleView?.let { subView ->
                    val h = subView.height.toFloat()
                    subView.pivotX = subView.width / 2f
                    subView.pivotY = h / 2f
                    val inverseScale = 1f / zoomScale
                    subView.scaleX = inverseScale
                    subView.scaleY = inverseScale

                    val safePx = SUBTITLE_BOTTOM_SAFE_MARGIN_DP * subView.resources.displayMetrics.density
                    val windowH = pv.height.toFloat()
                    val textBottom = (windowH - h) / 2f + SUBTITLE_TEXT_BOTTOM_FRACTION * h
                    val maxShift = (windowH - safePx - textBottom).coerceAtLeast(0f)
                    val desiredShift = ((zoomScale - 1f) * 0.5f * h).coerceAtLeast(0f)
                    subView.translationY = minOf(desiredShift, maxShift) / zoomScale
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

        // 2. 独立手势捕获层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPlayerGestures(
                        isLocked = { currentIsLocked },
                        isPlaying = { currentIsPlaying },
                        onSingleTap = {
                            showControls = !showControls
                            if (showControls) {
                                controlsInteractionTick++
                            }
                        },
                        onDoubleTap = {
                            if (!currentIsLocked) {
                                currentOnPlayPauseToggle()
                            }
                        },
                        onLongPressStart = {
                            Timber.d(
                                "viewport: onLongPressStart called! currentIsLocked=%s, currentIsPlaying=%s, players.isPlaying=%s",
                                currentIsLocked,
                                currentIsPlaying,
                                players.state.value.isPlaying,
                            )
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
                        onZoomStart = {
                            zoomResetJob?.cancel()
                            zoomGestureBase = zoomScale
                        },
                        onZoomChange = { factor ->
                            zoomScale = (zoomGestureBase * factor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                            zoomInteractionTick++
                        },
                    )
                },
        )

        val isLoading = isResolvingPlay || isAutoSearching || playerState.playbackState == PlaybackState.BUFFERING
        val isError = playerState.playbackState == PlaybackState.ERROR

        // 3. 加载 / 缓冲提示
        if (isLoading) {
            PlayerLoadingOverlay(
                isResolvingPlay = isResolvingPlay,
                isAutoSearching = isAutoSearching,
                autoSearchMessage = autoSearchMessage,
                networkSpeed = playerState.networkSpeed,
            )
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

        // 6. 无控制栏滑动快进时的底部极细预览进度条
        val dur = playerState.duration
        val gestureSeekProgress = if (dur > 0L) (gestureSeekTargetMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val bufferedProgress = if (dur > 0L) (playerState.bufferedPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
        AnimatedVisibility(
            visible = isSeekingByGesture && !showControls && !isLocked,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = insets.horizontal + 16.dp,
                    end = insets.horizontal + 16.dp,
                    bottom = insets.bottom + 14.dp,
                ),
        ) {
            PlayerSeekPreviewStrip(
                progress = gestureSeekProgress,
                bufferedProgress = bufferedProgress,
                openingRatio = if (opening > 0L && dur > 0L) (opening.toFloat() / dur).coerceIn(0f, 1f) else null,
                endingRatio = if (ending > 0L && dur > 0L) ((dur - ending).coerceAtLeast(0L).toFloat() / dur).coerceIn(0f, 1f) else null,
            )
        }

        // 7. 播放错误提示（带直达重新加载与切换线路入口）
        if (isError) {
            PlayerErrorOverlay(
                errorMessage = playerState.errorMessage,
                onRetry = {
                    showControls = false
                    onRefreshPlayback()
                },
                onSwitchLine = if (showEpisodeControls || hasMultiFlags) {
                    {
                        showControls = false
                        onOpenEpisodeDrawer()
                    }
                } else {
                    null
                },
            )
        }

        // 8. 屏幕锁定时仅显示解锁按钮
        if (isLocked) {
            PlayerLockedOverlay(
                visible = showControls,
                onUnlock = {
                    isLocked = false
                    controlsInteractionTick++
                },
            )
            return@BoxWithConstraints
        }

        // 9. 右侧双指缩放还原胶囊（缩放偏离 1.0x 且控制栏可见或刚缩放结束时出现）
        PlayerZoomResetPill(
            visible = isZoomModified(zoomScale) && (showControls || showZoomHintAfterPinch) && activeSheet == PlayerSheet.NONE,
            zoomScale = zoomScale,
            onResetZoom = {
                zoomResetJob?.cancel()
                zoomResetJob = scope.launch {
                    val startScale = zoomScale
                    animate(
                        initialValue = startScale,
                        targetValue = DEFAULT_ZOOM_SCALE,
                        animationSpec = tween(durationMillis = 220),
                    ) { value, _ ->
                        zoomScale = value
                    }
                    zoomGestureBase = DEFAULT_ZOOM_SCALE
                    showZoomHintAfterPinch = false
                }
                controlsInteractionTick++
            },
        )

        // 10. 主交互控制层
        PlayerControlsOverlay(
            visible = showControls,
            showNetworkSpeed = !isLoading && playerState.playbackState == PlaybackState.READY,
            vod = vod,
            playerState = playerState,
            isLandscape = isLandscape,
            currentEpisodeName = currentEpisodeName,
            hasPrevEpisode = hasPrevEpisode,
            hasNextEpisode = hasNextEpisode,
            hasMultiQuality = hasMultiQuality,
            currentQualityName = currentQualityName,
            opening = opening,
            ending = ending,
            externalSeekPosition = if (isSeekingByGesture) gestureSeekTargetMs else pendingGestureSeekTarget,
            onBack = onBack,
            onPlayPauseToggle = onPlayPauseToggle,
            onSeekTo = onSeekTo,
            onSeekBy = { players.seekBy(it) },
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onLockScreen = {
                isLocked = true
                showControls = true
                controlsInteractionTick++
            },
            onOpenSpeedDrawer = { openSheet(PlayerSheet.SPEED) },
            onOpenQualityDrawer = { openSheet(PlayerSheet.QUALITY) },
            onOpenTrackDrawer = { openSheet(PlayerSheet.TRACKS) },
            onOpenEpisodeDrawer = {
                showControls = false
                activeSheet = PlayerSheet.NONE
                onOpenEpisodeDrawer()
            },
            onOpenMoreDrawer = { openSheet(PlayerSheet.MORE) },
            onProgressDraggingChanged = { dragging ->
                isProgressBarDragging = dragging
                if (!dragging) {
                    controlsInteractionTick++
                }
            },
            onInteraction = { controlsInteractionTick++ },
        )
    }

    // 自适应悬浮抽屉 / 底部 Sheet（退出动画期间通过 lastActiveSheet 保活内容）
    val renderedSheet = if (activeSheet != PlayerSheet.NONE) activeSheet else lastActiveSheet
    PlayerAdaptiveSheetHost(
        visible = activeSheet != PlayerSheet.NONE,
        isLandscape = viewportIsLandscape,
    ) {
        when (renderedSheet) {
            PlayerSheet.SPEED -> {
                SpeedSelectionDrawer(
                    currentSpeed = playerState.speed,
                    isLandscape = viewportIsLandscape,
                    onSelectSpeed = { players.setPlaybackSpeed(it) },
                    onDismiss = { activeSheet = PlayerSheet.NONE },
                )
            }
            PlayerSheet.QUALITY -> {
                QualitySelectionDialog(
                    playUrls = playUrls,
                    selectedPlayUrl = selectedPlayUrl,
                    isLandscape = viewportIsLandscape,
                    onSelectPlayUrl = onSelectPlayUrl,
                    onDismiss = { activeSheet = PlayerSheet.NONE },
                )
            }
            PlayerSheet.TRACKS -> {
                UnifiedTrackDrawer(
                    subtitleTracks = playerState.subtitleTracks,
                    audioTracks = playerState.audioTracks,
                    videoTracks = playerState.videoTracks,
                    isSubtitleDisabled = playerState.isSubtitleDisabled,
                    isLandscape = viewportIsLandscape,
                    onSelectTrack = onSelectTrack,
                    onDisableSubtitle = { onDisableTrack(androidx.media3.common.C.TRACK_TYPE_TEXT) },
                    onResetToDefault = onResetToDefault,
                    onDismiss = { activeSheet = PlayerSheet.NONE },
                )
            }
            PlayerSheet.MORE, PlayerSheet.NONE -> {
                PlayerMoreDrawer(
                    vodName = vod.vodName,
                    currentEpisodeName = currentEpisodeName,
                    playerState = playerState,
                    opening = opening,
                    ending = ending,
                    isLandscape = viewportIsLandscape,
                    onRefreshPlayback = onRefreshPlayback,
                    onOpenTracksDrawer = { openSheet(PlayerSheet.TRACKS) },
                    onSelectResizeMode = { players.setResizeMode(it) },
                    onSetOpening = onSetOpening,
                    onResetOpening = onResetOpening,
                    onSetEnding = onSetEnding,
                    onResetEnding = onResetEnding,
                    onDismiss = { activeSheet = PlayerSheet.NONE },
                )
            }
        }
    }
}
