package com.mewbox.tv.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mewbox.tv.api.douban.WishStatus
import com.mewbox.tv.bean.History
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.LocalMewOverlayDismiss
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.components.glassTopBarSurface
import com.mewbox.tv.ui.theme.MewBoxTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onNavigateToPlay: (siteKey: String, vodId: String, vodName: String, vodPic: String) -> Unit,
    onNavigateToSearch: (keyword: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
    wishViewModel: WishViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wishState by wishViewModel.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var isDeleteMode by rememberSaveable { mutableStateOf(false) }
    // 0 = 播放记录（默认），1 = 豆瓣想看；rememberSaveable 保证切顶层 Tab 回来保持上次分段
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = segment, pageCount = { 2 })
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // 点击顶部文字 Tab 时同步平滑滑动到对应页
    LaunchedEffect(segment) {
        if (pagerState.currentPage != segment) {
            isProgrammaticScroll = true
            try {
                pagerState.animateScrollToPage(segment)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    // 左右滑动页面时同步更新 Tab 选中态
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                if (!isProgrammaticScroll && page != segment) {
                    segment = page
                }
            }
    }

    // 当列表为空时自动退出删除模式
    LaunchedEffect(state.items.isEmpty()) {
        if (state.items.isEmpty()) {
            isDeleteMode = false
        }
    }

    // 处于删除模式时拦截系统返回键，按返回键退出删除模式恢复默认状态
    BackHandler(enabled = isDeleteMode) {
        isDeleteMode = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isDeleteMode) {
                        Text("管理记录")
                    } else {
                        // 文字 Tab 直接占用标题位，与右侧删除/同步按钮同一行，省掉原来单独一行分段条
                        HistoryTextTabs(
                            selectedIndex = segment,
                            wishCount = wishState.total,
                            onSelect = { segment = it },
                        )
                    }
                },
                navigationIcon = {
                    if (isDeleteMode) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(40.dp)
                                .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                                .clickable { isDeleteMode = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "退出管理",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (isDeleteMode) {
                        TextButton(
                            onClick = { isDeleteMode = false },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = "完成",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else if (segment == 1) {
                        // 豆瓣想看分段：同步按钮（点击 30s 节流，长按强制同步）
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(40.dp)
                                .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                                .combinedClickable(
                                    onClick = { wishViewModel.sync() },
                                    onLongClick = { wishViewModel.sync(forced = true) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "同步豆瓣想看",
                                modifier = Modifier.size(20.dp),
                                tint = if (wishState.status == WishStatus.Refreshing) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    } else if (state.items.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(40.dp)
                                .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright )
                                .clickable { showClearDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "清空历史",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize().statusBarsPadding(),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isDeleteMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    if (page == 1 && !isDeleteMode) {
                        WishTab(
                            onNavigateToSearch = onNavigateToSearch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                state.isLoading -> {
                                    LoadingState(text = "正在获取观看历史...")
                                }
                                state.items.isEmpty() -> {
                                    EmptyState(message = "暂无记录")
                                }
                                else -> {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(state.items, key = { it.key }) { history ->
                                            HistoryItemCard(
                                                history = history,
                                                isDeleteMode = isDeleteMode,
                                                onClick = {
                                                    if (isDeleteMode) {
                                                        viewModel.deleteHistory(history.key)
                                                    } else {
                                                        onNavigateToPlay(history.siteKey, history.vodId, history.vodName, history.vodPic)
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!isDeleteMode) {
                                                        isDeleteMode = true
                                                    }
                                                },
                                                onDeleteClick = {
                                                    viewModel.deleteHistory(history.key)
                                                },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 清空当前订阅历史底部确认抽屉
    if (showClearDialog) {
        ClearHistorySheet(
            onConfirm = {
                viewModel.clearAll()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

@Composable
private fun ClearHistorySheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val dismiss = LocalMewOverlayDismiss.current ?: onDismiss
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "清空播放历史",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OverlayTokens.DialogTitle,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "确定要清除当前订阅下的所有播放历史记录吗？此操作不可恢复。",
                fontSize = 14.sp,
                color = OverlayTokens.TextSecondary,
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = dismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OverlayTokens.SheetItemBg,
                        contentColor = OverlayTokens.OutlinedText,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("清空", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/* ---------------- 追剧页顶部文字 Tab ---------------- */

/** 文字 Tab 行高度：放进 TopAppBar 的 title 位，与右侧删除/同步按钮同一行。 */
private val HistoryTabRowHeight = 44.dp

/** 两个 Tab 之间的间距：紧凑排列，不撑满整行。 */
private val HistoryTabSpacing = 24.dp

/**
 * 追剧页顶部文字 Tab：选中项主色文字 + 底部红色指示条。
 *
 * Tab 按文字实际宽度靠左紧凑排列（不是等分整行），指示条测量每个 Tab 的真实边界后
 * 用 offset 动画贴合，宽度与文字等宽。占用 TopAppBar.title 不额外占行。
 */
@Composable
private fun HistoryTextTabs(
    selectedIndex: Int,
    wishCount: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf("播放记录", "豆瓣想看")
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    // 测量每个 Tab 的横向位置与宽度（相对本组件左边缘），指示条据此贴合真实文字
    var bounds0 by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var bounds1 by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val selectedBounds = if (selectedIndex == 0) bounds0 else bounds1

    val indicatorX by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.first.toDp() } } ?: 0.dp,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "HistoryTabIndicatorX",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.second.toDp() } } ?: 0.dp,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "HistoryTabIndicatorWidth",
    )

    Box(
        modifier = modifier
            .height(HistoryTabRowHeight),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = indicatorX, y = (-8).dp)
                .width(indicatorWidth)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HistoryTabSpacing),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    targetValue = if (selected) accent else muted,
                    animationSpec = tween(durationMillis = 200),
                    label = "HistoryTabText$index",
                )
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val x = coords.positionInParent().x
                            val w = coords.size.width.toFloat()
                            val old = if (index == 0) bounds0 else bounds1
                            if (old == null || abs(old.first - x) > 1f || abs(old.second - w) > 1f) {
                                if (index == 0) bounds0 = x to w else bounds1 = x to w
                            }
                        }
                        .clickable(onClickLabel = label) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = color,
                            maxLines = 1,
                        )
                        if (index == 1 && wishCount > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (wishCount > 999) "999+" else wishCount.toString(),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = color.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemCard(
    history: History,
    isDeleteMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = if (isDeleteMode) "删除记录" else "继续播放",
                onLongClickLabel = if (isDeleteMode) null else "管理记录",
                onLongClick = if (isDeleteMode) null else onLongClick,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧海报图（小尺寸，高宽比约 4:3 或 16:9）
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                var loadFailed by remember(history.vodPic) { mutableStateOf(history.vodPic.isBlank()) }
                if (loadFailed) {
                    PosterPlaceholder(
                        isHero = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = history.vodPic,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) {
                                loadFailed = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 中间剧集信息与续播进度
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = history.vodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MewBoxTheme.colors.primarySoftBg,
                    ) {
                        Text(
                            text = history.siteName.ifEmpty { history.siteKey },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "线路: ${history.vodFlag}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "上次观看：${history.episodeName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                val progressProgress = if (history.duration > 0L) {
                    (history.position.toFloat() / history.duration).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { progressProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = HistoryViewModel.formatProgress(history.position, history.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右侧动作按钮：始终固定占位（36dp），只在图标/配色间切换，
            // 避免进入删除模式时横向展开挤压左侧的剧集信息
            val actionContainerColor by animateColorAsState(
                targetValue = MaterialTheme.colorScheme.primaryContainer,
                label = "HistoryActionContainerColor",
            )
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(actionContainerColor)
                    .combinedClickable(
                        onClickLabel = if (isDeleteMode) "删除记录" else "继续播放",
                        onLongClickLabel = if (isDeleteMode) null else "管理记录",
                        onLongClick = if (isDeleteMode) null else onLongClick,
                        onClick = if (isDeleteMode) onDeleteClick else onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = isDeleteMode,
                    label = "HistoryActionIcon",
                ) { deleteMode ->
                    Icon(
                        imageVector = if (deleteMode) Icons.Rounded.DeleteOutline else Icons.Rounded.PlayArrow,
                        contentDescription = if (deleteMode) "删除" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
