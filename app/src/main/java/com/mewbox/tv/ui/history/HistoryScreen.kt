package com.mewbox.tv.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mewbox.tv.api.douban.WishStatus
import com.mewbox.tv.bean.History
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.components.glassTopBarSurface
import com.mewbox.tv.ui.theme.OrangeBadge

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
                title = { Text(if (isDeleteMode) "管理记录" else "追剧") },
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
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "退出管理",
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
                                imageVector = Icons.Default.Refresh,
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
                                .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                                .clickable { showClearDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清空历史",
                                modifier = Modifier.size(20.dp),
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
                // 顶部分段切换（删除模式属于播放记录的局部状态，进入时隐藏分段避免焦点跨栏）
                if (!isDeleteMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SegmentPill(
                            text = "播放记录",
                            selected = segment == 0,
                            onClick = { segment = 0 },
                        )
                        SegmentPill(
                            text = if (wishState.total > 0) "豆瓣想看 ${wishState.total}" else "豆瓣想看",
                            selected = segment == 1,
                            onClick = { segment = 1 },
                        )
                    }
                }

                if (segment == 1 && !isDeleteMode) {
                    WishTab(
                        onNavigateToSearch = onNavigateToSearch,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    ) {
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

    // 清空当前订阅历史确认弹窗
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空播放历史") },
            text = { Text("确定要清除当前订阅下的所有播放历史记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearDialog = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 追剧页顶部分段切换 pill：选中态用主题主色胶囊。 */
@Composable
private fun SegmentPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = OrangeBadge,
                    ) {
                        Text(
                            text = history.siteName.ifEmpty { history.siteKey },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
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
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 右侧动作按钮：始终固定占位（36dp），只在图标/配色间切换，
            // 避免进入删除模式时横向展开挤压左侧的剧集信息
            val actionContainerColor by animateColorAsState(
                targetValue = if (isDeleteMode) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
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
                        imageVector = if (deleteMode) Icons.Default.DeleteOutline else Icons.Default.PlayArrow,
                        contentDescription = if (deleteMode) "删除" else "播放",
                        tint = if (deleteMode) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
