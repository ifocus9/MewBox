package com.mewbox.tv.ui.detail

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.Players
import com.mewbox.tv.ui.components.ErrorState
import com.mewbox.tv.ui.components.LoadingState

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.players.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var isContentExpanded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }

    // 拦截全屏返回
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    // 页面销毁时重置回竖屏
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    // 全屏沉浸式系统栏显示与隐藏
    LaunchedEffect(isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    // 全屏模式下：直接全屏铺满播放视窗
    if (isFullscreen && state.vod != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayerViewport(
                vod = state.vod!!,
                players = viewModel.players,
                playerState = playerState,
                isFullscreen = true,
                currentEpisodeName = state.selectedEpisode?.name ?: "",
                isResolvingPlay = state.isResolvingPlay,
                isAutoSearching = state.isAutoSearching,
                autoSearchMessage = state.autoSearchMessage,
                onPlayPauseToggle = { viewModel.players.togglePlayPause() },
                onSeekTo = { viewModel.players.seekTo(it) },
                onToggleFullscreen = {
                    isFullscreen = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    // 常规竖屏模式
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.vod?.vodName ?: "影片详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.openChangeSourceDialog() }) {
                        Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("换源 (${state.changeSources.size})")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> {
                    LoadingState(text = "正在获取影片详情与分集线路...")
                }
                state.error != null -> {
                    ErrorState(
                        message = state.error ?: "加载失败",
                        onRetry = {
                            viewModel.loadDetail(
                                state.currentSite?.key ?: "",
                                state.vod?.vodId ?: "",
                                state.vod?.vodName ?: "",
                            )
                        },
                    )
                }
                state.vod != null -> {
                    val vod = state.vod!!

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 1. 播放器视窗区域（竖屏 16:9）
                        VideoPlayerViewport(
                            vod = vod,
                            players = viewModel.players,
                            playerState = playerState,
                            isFullscreen = false,
                            currentEpisodeName = state.selectedEpisode?.name ?: "",
                            isResolvingPlay = state.isResolvingPlay,
                            isAutoSearching = state.isAutoSearching,
                            autoSearchMessage = state.autoSearchMessage,
                            onPlayPauseToggle = { viewModel.players.togglePlayPause() },
                            onSeekTo = { viewModel.players.seekTo(it) },
                            onToggleFullscreen = {
                                isFullscreen = true
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                        )

                        // 2. 影片基础信息卡片
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = vod.vodName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = { viewModel.openChangeSourceDialog() },
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = state.currentSite?.name ?: "切换源")
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (vod.vodYear.isNotEmpty()) {
                                    Text(vod.vodYear, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                if (vod.vodArea.isNotEmpty()) {
                                    Text(vod.vodArea, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                if (vod.vodRemarks.isNotEmpty()) {
                                    Text(vod.vodRemarks, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            if (vod.vodActor.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "演员：${vod.vodActor}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // 剧情简介折叠
                            if (vod.vodContent.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = vod.vodContent.replace("<[^>]*>".toRegex(), "").trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (isContentExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { isContentExpanded = !isContentExpanded },
                                )
                            }
                        }

                        // 3. 豆瓣纯推荐源提示卡片
                        if (state.isMsearch) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (state.isAutoSearching) "正在全网为您匹配播放源..."
                                        else "当前为推荐片单。已自动通过跨源检索完成直链嗅探；如需切换请点击【换源】。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }

                        // 4. 多线路切换 Tab
                        if (vod.playFlags.size > 1) {
                            val selectedIndex = vod.playFlags.indexOf(state.selectedFlag).coerceAtLeast(0)
                            ScrollableTabRow(
                                selectedTabIndex = selectedIndex,
                                edgePadding = 16.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                vod.playFlags.forEach { flag ->
                                    Tab(
                                        selected = flag == state.selectedFlag,
                                        onClick = { viewModel.selectFlag(flag) },
                                        text = { Text(flag.flag) },
                                    )
                                }
                            }
                        }

                        // 5. 选集网格
                        val currentEpisodes = state.selectedFlag?.episodes ?: emptyList()
                        if (currentEpisodes.isNotEmpty()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "选集 (${currentEpisodes.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "线路: ${state.selectedFlag?.flag ?: "默认"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                // 多清晰度（网盘源，如 无限4K / 1080P）
                                if (state.playUrls.size > 1) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "清晰度:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        state.playUrls.forEach { pu ->
                                            val isSelected = pu.url == state.selectedPlayUrl?.url
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { viewModel.selectPlayUrl(pu) },
                                                label = { Text(pu.name, style = MaterialTheme.typography.labelSmall) },
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    currentEpisodes.forEach { ep ->
                                        val isSelected = ep == state.selectedEpisode
                                        SuggestionChip(
                                            onClick = { viewModel.selectEpisode(ep) },
                                            label = { Text(ep.name) },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                                labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 换源弹窗
            if (state.showChangeSourceDialog) {
                ChangeSourceDialog(
                    currentSiteKey = state.currentSite?.key ?: "",
                    sources = state.changeSources,
                    isSearching = state.isSearchingChangeSources,
                    onSelect = { viewModel.changeSource(it) },
                    onDismiss = { viewModel.closeChangeSourceDialog() },
                )
            }
        }
    }
}

/**
 * 播放器视窗组件：内嵌 ExoPlayer PlayerView，包含遮罩、进度条、全屏切换与快进快退控制
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerViewport(
    vod: Vod,
    players: Players,
    playerState: com.mewbox.tv.player.PlayerState,
    isFullscreen: Boolean,
    currentEpisodeName: String,
    isResolvingPlay: Boolean,
    isAutoSearching: Boolean,
    autoSearchMessage: String?,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showControls by remember { mutableStateOf(true) }
    var userDraggingProgress by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable { showControls = !showControls },
        contentAlignment = Alignment.Center,
    ) {
        // 底层 PlayerView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    player = players.exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 占位海报（未开始播放或出错时）
        if (playerState.playbackState == PlaybackState.IDLE && vod.vodPic.isNotEmpty()) {
            AsyncImage(
                model = vod.vodPic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 加载或跨源检索中的 Loading 提示
        if (isResolvingPlay || isAutoSearching || playerState.playbackState == PlaybackState.BUFFERING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = when {
                            isAutoSearching -> autoSearchMessage ?: "正在跨源检索全网可用源..."
                            isResolvingPlay -> "正在解析播放直链..."
                            else -> "正在缓冲视频..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }

        // 播放错误提示
        if (playerState.playbackState == PlaybackState.ERROR) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("播放失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(playerState.errorMessage ?: "解析直链失败", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 自定义覆盖控制层（淡入淡出遮罩）
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x77000000),
                                Color.Transparent,
                                Color(0x99000000),
                            )
                        )
                    ),
            ) {
                // 全屏模式顶部栏：标题与退出全屏小按钮
                if (isFullscreen) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "退出全屏",
                                tint = Color.White,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${vod.vodName} ${if (currentEpisodeName.isNotEmpty()) "· $currentEpisodeName" else ""}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 中心控制器：快退 10s、播放/暂停、快进 10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    IconButton(
                        onClick = { onSeekTo((playerState.currentPosition - 10_000L).coerceAtLeast(0L)) },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0x55000000), CircleShape),
                    ) {
                        Icon(imageVector = Icons.Default.Replay10, contentDescription = "快退10秒", tint = Color.White)
                    }

                    IconButton(
                        onClick = onPlayPauseToggle,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0x66000000), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    IconButton(
                        onClick = { onSeekTo((playerState.currentPosition + 10_000L).coerceAtMost(playerState.duration)) },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0x55000000), CircleShape),
                    ) {
                        Icon(imageVector = Icons.Default.Forward10, contentDescription = "快进10秒", tint = Color.White)
                    }
                }

                // 底部控制器：进度条、时间与全屏按钮
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    val duration = playerState.duration
                    val current = playerState.currentPosition

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = Players.formatDuration(if (userDraggingProgress) (sliderPosition * duration).toLong() else current),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = " / ${Players.formatDuration(duration)}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "退出全屏" else "全屏播放",
                                tint = Color.White,
                            )
                        }
                    }

                    Slider(
                        value = if (userDraggingProgress) sliderPosition
                        else if (duration > 0) (current.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = {
                            userDraggingProgress = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            userDraggingProgress = false
                            onSeekTo((sliderPosition * duration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 换源弹窗
 */
@Composable
private fun ChangeSourceDialog(
    currentSiteKey: String,
    sources: List<Vod>,
    isSearching: Boolean,
    onSelect: (Vod) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择播放源", modifier = Modifier.weight(1f))
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isSearching) "正在检索全网可用源..." else "暂无其它可用源")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sources, key = { "${it.siteKey}_${it.vodId}" }) { item ->
                        val isSelected = item.siteKey == currentSiteKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.siteName.ifEmpty { item.siteKey },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (item.vodRemarks.isNotEmpty()) {
                                    Text(
                                        text = item.vodRemarks,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "当前选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
