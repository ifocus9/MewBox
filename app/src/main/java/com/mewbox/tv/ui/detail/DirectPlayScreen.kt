package com.mewbox.tv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.player.FullScreenEpisodeDrawer
import com.mewbox.tv.ui.player.PlayerToastPill
import com.mewbox.tv.ui.player.PlayerWindowEffects
import com.mewbox.tv.ui.player.VideoPlayerViewport
import com.mewbox.tv.ui.theme.MewBoxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 历史记录直达全屏播放页：
 * - 纯黑底色直接进入全屏视窗，彻底消除海报详情页的闪烁与无谓转场
 * - 复用成熟的 [DetailViewModel]（处理选集、换源、解析与历史记录持久化）
 * - 退出时直接返回历史列表，不经过海报页
 */
@Composable
fun DirectPlayScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.players.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFullscreenEpisodeDrawer by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }

    fun exit() {
        if (showFullscreenEpisodeDrawer) {
            showFullscreenEpisodeDrawer = false
            return
        }
        if (isExiting) return
        isExiting = true
        viewModel.onExitFullscreen()
        viewModel.players.stop()
        onBack()
    }

    // 动作类拦截条目直接关闭退出
    LaunchedEffect(state.closeScreen) {
        if (state.closeScreen) {
            exit()
        }
    }

    // 详情或解析失败时，提示并延迟自动退出
    LaunchedEffect(state.error) {
        if (state.error != null && state.vod?.playFlags.isNullOrEmpty()) {
            snackbarHostState.showSnackbar(state.error ?: "加载失败")
            delay(1500)
            exit()
        }
    }

    // Toast 提示
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
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
            viewModel.clearToast()
        }
    }

    // 拦截物理/手势返回键
    BackHandler {
        exit()
    }

    MewBoxTheme(darkTheme = true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            PlayerWindowEffects(
                isFullscreen = true,
                playerState = playerState,
                keepScreenOnExtra = state.isResolvingPlay || state.isAutoSearching || state.isLoading,
            )

            val vod = state.vod ?: Vod(
                vodId = "",
                vodName = "",
                vodPic = "",
            )

            VideoPlayerViewport(
                vod = vod,
                players = viewModel.players,
                playerState = playerState,
                currentEpisodeName = state.selectedEpisode?.name ?: "",
                hasPrevEpisode = state.hasPreviousEpisode,
                hasNextEpisode = state.hasNextEpisode,
                isResolvingPlay = state.isResolvingPlay || (state.isLoading && !state.isAutoSearching),
                isAutoSearching = state.isAutoSearching,
                autoSearchMessage = state.autoSearchMessage,
                onBack = { exit() },
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
                    onSelectEpisode = { ep ->
                        viewModel.selectEpisode(ep)
                        showFullscreenEpisodeDrawer = false
                    },
                    onClose = { showFullscreenEpisodeDrawer = false },
                )
            }

            // 退出时的纯黑遮罩：彻底避免转回竖屏和出栈期间露出下层内容
            if (isExiting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 76.dp),
            ) { data ->
                PlayerToastPill(message = data.visuals.message)
            }
        }
    }
}

