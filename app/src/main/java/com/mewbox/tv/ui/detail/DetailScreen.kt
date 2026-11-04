@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.mewbox.tv.ui.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.ui.components.ErrorState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.player.FullScreenEpisodeDrawer
import com.mewbox.tv.ui.player.PlayerToastPill
import com.mewbox.tv.ui.player.PlayerWindowEffects
import com.mewbox.tv.ui.player.PresentationMode
import com.mewbox.tv.ui.player.VideoPlayerViewport
import com.mewbox.tv.ui.theme.DynamicTheme
import com.mewbox.tv.ui.theme.ImagePalette
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.ui.theme.PosterSeedMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EntrySlideDurationMs = 300
private const val ContentSlideRatio = 0.2f

/**
 * 影片详情页 / 全屏播放器调度容器（完全移植 AVBox 播放流程）
 * 常规点击影片：展示大海报影片详情页，点击播放胶囊或选集进入全屏播放
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.players.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 若当前条目属于动作拦截（如触发 nodejs webview 配置），立即静默关闭退出，防止海报详情页闪烁
    LaunchedEffect(state.closeScreen) {
        if (state.closeScreen) {
            onBack()
        }
    }

    if (state.closeScreen) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }

    var presentation by remember {
        mutableStateOf(PresentationMode.HIDDEN)
    }
    var enteringFullscreen by remember { mutableStateOf(false) }
    var exitingFullscreen by remember { mutableStateOf(false) }

    val inTransition = enteringFullscreen || exitingFullscreen
    val isPlayerMounted = presentation.isMounted || inTransition
    var slideWidthPx by remember { mutableFloatStateOf(0f) }
    val slideAnim = remember { Animatable(0f) }

    val slideProgress = if (inTransition) slideAnim.value else if (presentation.isFullscreen) 1f else 0f
    var showFullscreenEpisodeDrawer by remember { mutableStateOf(false) }

    fun enterPlayer() {
        if (presentation.isFullscreen || enteringFullscreen) return
        exitingFullscreen = false
        enteringFullscreen = true
    }

    // 统一的退出播放动作：返回键与播放器返回按钮共用同一份逻辑
    fun exitPlayer() {
        if (exitingFullscreen) return
        if (enteringFullscreen) {
            enteringFullscreen = false
        }
        exitingFullscreen = true
        viewModel.onExitFullscreen()
    }

    // 全屏滑动转场动画驱动（进入 300ms 滑入，退出 300ms 滑出）
    LaunchedEffect(enteringFullscreen, exitingFullscreen) {
        when {
            exitingFullscreen -> {
                slideAnim.animateTo(0f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                presentation = PresentationMode.HIDDEN
                exitingFullscreen = false
            }
            enteringFullscreen -> {
                slideAnim.animateTo(1f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                presentation = PresentationMode.FULLSCREEN
                enteringFullscreen = false
            }
        }
    }

    val initialPic = viewModel.initialVodPic.ifBlank { state.vod?.vodPic.orEmpty() }

    val backdropUrl = state.tmdbDetail?.backdropPath?.let {
        TmdbClient.imageUrl(state.tmdbImageBase, it, "w1280")
    } ?: state.vod?.vodPic

    var backdropSeed by remember {
        mutableStateOf(PosterSeedMemory.seedOf(initialPic) ?: PosterSeedMemory.seedOf(backdropUrl))
    }

    // 当 backdropUrl 或 vodPic 发生变化时：
    // 若新图片已有缓存种子色，立即切换；若尚无缓存，保留当前海报色平滑过渡，切勿重置为 null 导致跳变回默认蓝色
    LaunchedEffect(backdropUrl, state.vod?.vodPic) {
        val candidate = PosterSeedMemory.seedOf(backdropUrl)
            ?: PosterSeedMemory.seedOf(state.vod?.vodPic)
        if (candidate != null && backdropSeed != candidate) {
            backdropSeed = candidate
        }
    }

    val dynamicColorScheme = backdropSeed?.let { seed ->
        remember(seed) { DynamicTheme.customScheme(seed, isDark = true) }
    }

    // 当 ViewModel 触发全屏起播（如豆瓣推荐源跨源检索起播）时同步呈现形态
    LaunchedEffect(state.startInFullscreen) {
        if (state.startInFullscreen && presentation == PresentationMode.HIDDEN && !enteringFullscreen) {
            enterPlayer()
        }
    }

    // 播放窗口副作用集中管理（屏幕旋转、沉浸式系统栏、防息屏常亮与恢复）
    PlayerWindowEffects(
        isFullscreen = presentation.isFullscreen,
        playerState = playerState,
        keepScreenOnExtra = state.isResolvingPlay || state.isAutoSearching,
    )

    // 拦截物理/手势返回键：
    BackHandler(enabled = isPlayerMounted) {
        when {
            inTransition && enteringFullscreen -> {
                // 进场动画中用户按返回：优雅取消进场动画，反向退回
                enteringFullscreen = false
                exitingFullscreen = true
                viewModel.onExitFullscreen()
            }
            showFullscreenEpisodeDrawer -> {
                // 全屏侧滑选集抽屉打开时，优先关闭抽屉
                showFullscreenEpisodeDrawer = false
            }
            presentation.isFullscreen && !exitingFullscreen -> {
                // 全屏中按返回：触发平滑滑出退场
                exitPlayer()
            }
        }
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            if (presentation.isFullscreen) {
                snackbarHostState.currentSnackbarData?.dismiss()
                val job = launch {
                    snackbarHostState.showSnackbar(
                        message = msg,
                        duration = SnackbarDuration.Indefinite,
                    )
                }
                delay(1800)
                snackbarHostState.currentSnackbarData?.dismiss()
                job.cancel()
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            viewModel.clearToast()
        }
    }

    // ==================== 统一界面容器（AVBox 风格，沉浸式透明状态栏 & 海报动态取色） ====================
    MewBoxTheme(darkTheme = true, colorScheme = dynamicColorScheme) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { slideWidthPx = it.width.toFloat() }
                .background(Color.Black),
        ) {
            // 背景虚化海报（始终平铺在最底层，加载期间即可展示海报底图并提取种子色）
            val currentBackdrop = backdropUrl ?: state.vod?.vodPic
            PosterBackdrop(
                pic = currentBackdrop,
                onImage = { image ->
                    scope.launch(Dispatchers.Default) {
                        val seed = ImagePalette.seedOf(image)
                        if (seed != null) {
                            PosterSeedMemory.put(currentBackdrop, seed)
                            if (backdropSeed != seed) {
                                backdropSeed = seed
                            }
                        }
                    }
                },
            )

            when {
                // 1. 全局 Loading 状态：正在获取影片详情或分集信息，统一展示系统级 Loading（对齐 AVBox）
                // 顶栏（返回按钮）在拿到影片信息后才展示，加载期仅显示背景海报 + Loading
                state.isLoading -> {
                    LoadingState(text = "")
                }

                // 2. 详情获取失败状态
                state.error != null && state.vod?.playFlags.isNullOrEmpty() -> {
                    ErrorState(
                        message = state.error ?: "加载失败",
                        onRetry = { viewModel.retry() },
                    )

                    DetailTopScrim(modifier = Modifier.align(Alignment.TopCenter))
                    DetailTopBar(
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                // 3. 详情就绪：海报详情层与全屏播放器层同级叠加（支持 300ms 视差滑入/滑出转场）
                state.vod != null -> {
                    val vod = requireNotNull(state.vod)

                    // 3.1 海报详情内容主体（带视差负偏移：当 slideProgress 从 0 -> 1 时向左平移 20% 屏幕宽度）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset((-slideProgress * slideWidthPx * ContentSlideRatio).toInt(), 0)
                            },
                    ) {
                        DetailContent(
                            vod = vod,
                            tmdbDetail = state.tmdbDetail,
                            tmdbImageBase = state.tmdbImageBase,
                            flags = vod.playFlags,
                            currentFlag = state.selectedFlag,
                            episodes = state.selectedFlag?.episodes ?: emptyList(),
                            currentEpisode = state.selectedEpisode,
                            isEpisodeReversed = state.isEpisodeReversed,
                            isCompactEpisodeTitle = state.isCompactEpisodeTitle,
                            changeSources = state.changeSources,
                            currentSiteKey = state.currentSite?.key ?: "",
                            currentSourceName = state.currentSite?.name ?: "",
                            resumeEpisodeText = state.resumeEpisodeText,
                            onPlay = {
                                viewModel.startPlayback()
                                enterPlayer()
                            },
                            onToggleReverse = { viewModel.toggleEpisodeSort() },
                            onToggleCompact = { viewModel.toggleEpisodeCompact() },
                            onSelectEpisode = { ep ->
                                viewModel.selectEpisode(ep)
                                enterPlayer()
                            },
                            onSelectFlag = { flag -> viewModel.selectFlag(flag) },
                            onSelectSource = { targetVod -> viewModel.changeSource(targetVod, shouldAutoPlay = false) },
                        )

                        // TMDB 手动重新匹配弹窗
                        if (state.showTmdbMatchDialog) {
                            TmdbRematchDialog(
                                initialKeyword = state.tmdbSearchKeyword,
                                results = state.tmdbSearchResults,
                                isSearching = state.isSearchingTmdb,
                                searchError = state.tmdbSearchError,
                                isConfigured = state.isTmdbConfigured,
                                imageBase = state.tmdbImageBase,
                                onSearch = { viewModel.searchTmdb(it) },
                                onSelect = { viewModel.applyTmdbMatch(it) },
                                onDismiss = { viewModel.closeTmdbMatchDialog() },
                            )
                        }
                    }

                    // 3.2 顶部渐变遮罩与固定顶部工具栏（信息加载完成后 300ms 淡入；随转场进度平滑淡出，全屏时彻底不响应点击）
                    val topBarAlpha = (1f - slideProgress).coerceIn(0f, 1f)
                    val topBarVisible = topBarAlpha > 0f
                    // 初始为不可见 + 目标置 true：保证首次进入就绪态时播放淡入动画
                    val topBarVisibilityState = remember { MutableTransitionState(false) }
                    LaunchedEffect(topBarVisible) {
                        topBarVisibilityState.targetState = topBarVisible
                    }
                    AnimatedVisibility(
                        visibleState = topBarVisibilityState,
                        enter = fadeIn(tween(EntrySlideDurationMs, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(EntrySlideDurationMs, easing = FastOutSlowInEasing)),
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            DetailTopScrim()
                            DetailTopBar(
                                onBack = onBack,
                                onEditTmdb = { viewModel.openTmdbMatchDialog() },
                                modifier = Modifier.graphicsLayer { alpha = topBarAlpha },
                            )
                        }
                    }

                    // 3.3 全屏播放器视窗（挂载或转场时显示，从屏幕右侧 100% 宽度平滑滑入至全屏）
                    if (isPlayerMounted) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset {
                                    IntOffset(((1f - slideProgress) * slideWidthPx).toInt(), 0)
                                }
                                .background(Color.Black),
                        ) {
                            VideoPlayerViewport(
                                vod = vod,
                                players = viewModel.players,
                                playerState = playerState,
                                currentEpisodeName = state.selectedEpisode?.name ?: "",
                                hasPrevEpisode = state.hasPreviousEpisode,
                                hasNextEpisode = state.hasNextEpisode,
                                isResolvingPlay = state.isResolvingPlay,
                                isAutoSearching = state.isAutoSearching,
                                autoSearchMessage = state.autoSearchMessage,
                                onBack = { exitPlayer() },
                                onPlayPauseToggle = { viewModel.players.togglePlayPause() },
                                onSeekTo = { viewModel.players.seekTo(it) },
                                onPlayPrevious = { viewModel.playPreviousEpisode() },
                                onPlayNext = { viewModel.playNextEpisode() },
                                onRefreshPlayback = { viewModel.refreshPlayback() },
                                onOpenEpisodeDrawer = { showFullscreenEpisodeDrawer = true },
                                opening = state.opening,
                                ending = state.ending,
                                onSetOpening = { viewModel.setOpening() },
                                onResetOpening = { viewModel.resetOpening() },
                                onSetEnding = { viewModel.setEnding() },
                                onResetEnding = { viewModel.resetEnding() },
                                modifier = Modifier.fillMaxSize(),
                            )

                            // 全屏侧滑选集抽屉
                            AnimatedVisibility(
                                visible = showFullscreenEpisodeDrawer,
                                enter = slideInHorizontally(initialOffsetX = { it }),
                                exit = slideOutHorizontally(targetOffsetX = { it }),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                FullScreenEpisodeDrawer(
                                    episodes = state.selectedFlag?.episodes ?: emptyList(),
                                    currentEpisode = state.selectedEpisode,
                                    isReversed = state.isEpisodeReversed,
                                    isCompactEpisodeTitle = state.isCompactEpisodeTitle,
                                    onToggleSort = { viewModel.toggleEpisodeSort() },
                                    onToggleCompact = { viewModel.toggleEpisodeCompact() },
                                    onSelectEpisode = {
                                        viewModel.selectEpisode(it)
                                        showFullscreenEpisodeDrawer = false
                                    },
                                    onClose = { showFullscreenEpisodeDrawer = false },
                                )
                            }
                        }
                    }

                    // 3.4 转场动画期间手势拦截层：防止动画过程中误触任何下层或上层控件
                    if (inTransition) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                        }
                                    }
                                },
                        )
                    }
                }
            }

            // Snackbar 提示宿主（底部导航条安全区对齐）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = if (presentation.isFullscreen) 76.dp else 16.dp),
            ) { data ->
                PlayerToastPill(message = data.visuals.message)
            }
        }
    }
}
