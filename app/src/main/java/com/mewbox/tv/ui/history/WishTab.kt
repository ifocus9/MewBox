package com.mewbox.tv.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.mewbox.tv.api.douban.DoubanApi
import com.mewbox.tv.api.douban.WishItem
import com.mewbox.tv.api.douban.WishStatus
import com.mewbox.tv.api.douban.WishUiState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.setting.DoubanDialog
import com.mewbox.tv.ui.theme.BluePrimary
import com.mewbox.tv.ui.theme.OrangeBadge
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** 海报与右侧信息区共用固定高度，保证两栏上下边缘严格对齐。 */
private val WishPosterWidth = 96.dp
private val WishPosterHeight = 128.dp

/** 豆瓣评分星色（与豆瓣站内一致）。 */
private val DoubanStarColor = Color(0xFFFFAC2D)

/**
 * 追剧页「豆瓣想看」分段：竖向滚动列表 + 焦点接近末尾自动预加载。
 * 点击卡片跳全源搜索（关键词只用纯片名，不拼年份/类型）。
 */
@Composable
fun WishTab(
    onNavigateToSearch: (keyword: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WishViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showUidDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    when {
        state.status == WishStatus.NoUid -> {
            NoUidState(
                onFillUid = { showUidDialog = true },
                modifier = modifier.fillMaxSize(),
            )
        }

        state.status == WishStatus.Loading && state.items.isEmpty() -> {
            LoadingState(text = "正在同步豆瓣想看...", modifier = modifier)
        }

        state.status == WishStatus.Empty -> {
            WishMessageState(
                message = "未获取到想看条目",
                hint = "请检查豆瓣 uid 是否正确，以及账号隐私设置是否公开想看列表",
                actionText = "重新同步",
                onAction = { viewModel.sync(forced = true) },
                modifier = modifier.fillMaxSize(),
            )
        }

        state.status == WishStatus.Error && state.items.isEmpty() -> {
            WishMessageState(
                message = state.errorMessage ?: "同步失败",
                hint = null,
                actionText = "重试",
                onAction = { viewModel.retry() },
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> WishList(
            state = state,
            onNavigateToSearch = onNavigateToSearch,
            onLoadMore = { viewModel.loadMore() },
            onRetry = { viewModel.retry() },
            modifier = modifier,
        )
    }

    if (showUidDialog) {
        DoubanDialog(
            initialUid = "",
            onSave = {
                viewModel.saveUid(it)
                showUidDialog = false
            },
            onDismiss = { showUidDialog = false },
        )
    }
}

@Composable
private fun WishList(
    state: WishUiState,
    onNavigateToSearch: (keyword: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // TV 焦点接近列表末尾（倒数第 4 项）时自动预加载下一页
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to state.items.size
        }
            .distinctUntilChanged()
            .collect { (last, size) ->
                if (size > 0 && last >= size - 4) onLoadMore()
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 同步中顶部细进度条（不抢焦点），已加载列表保持可见
        if (state.status == WishStatus.Refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.interestId }) { item ->
                WishItemCard(
                    item = item,
                    onClick = { onNavigateToSearch(item.title) },
                    modifier = Modifier.animateItem(),
                )
            }

            // 尾部状态条：不参与 items.size 游标计算，loading 态不可聚焦
            when {
                state.status == WishStatus.LoadingMore -> {
                    item("wish_tail_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                state.status == WishStatus.Error && state.items.isNotEmpty() -> {
                    item("wish_tail_error") {
                        Text(
                            text = "加载失败，点击重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRetry() }
                                .padding(vertical = 14.dp),
                        )
                    }
                }

                !state.hasMore && state.items.isNotEmpty() -> {
                    item("wish_tail_done") {
                        Text(
                            text = "已全部加载 · 共 ${state.items.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoUidState(
    onFillUid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "尚未设置豆瓣账号",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "填写豆瓣个人 uid 后，即可在此同步你的「想看」清单",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onFillUid) {
            Text("填写豆瓣 uid")
        }
    }
}

@Composable
private fun WishMessageState(
    message: String,
    hint: String?,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(actionText)
        }
    }
}

@Composable
private fun WishItemCard(
    item: WishItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 左侧竖版海报（豆瓣 CDN 防盗链：必须同时注入 Referer 与浏览器 UA）
            Box(
                modifier = Modifier
                    .size(width = WishPosterWidth, height = WishPosterHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                var loadFailed by remember(item.poster) { mutableStateOf(item.poster.isBlank()) }
                if (loadFailed) {
                    PosterPlaceholder(
                        title = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val context = LocalContext.current
                    val request = remember(context, item.poster) {
                        ImageRequest.Builder(context)
                            .data(item.poster)
                            // 豆瓣 CDN 防盗链双重校验（2026-11 实测）：缺 Referer → 418；okhttp UA → 403。
                            // 必须同时注入 Referer 与浏览器 UA，二者缺一都拿不到图。
                            .httpHeaders(
                                NetworkHeaders.Builder()
                                    .set("Referer", DoubanApi.DOUBAN_REFERER)
                                    .set("User-Agent", DoubanApi.BROWSER_UA)
                                    .build(),
                            )
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) {
                                Timber.w(state.result.throwable, "豆瓣海报加载失败: %s", item.poster)
                                loadFailed = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 与海报等高：首行贴海报顶、末行贴海报底，中间各行自上而下均匀铺满
            Column(
                modifier = Modifier
                    .height(WishPosterHeight)
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 类型徽标 + 年份：独立一行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (item.isTv) BluePrimary else OrangeBadge,
                    ) {
                        Text(
                            text = if (item.isTv) "剧集" else "电影",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    if (item.year.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.year,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 豆瓣同款五星 + 分数：独立一行
                DoubanRatingRow(rating = item.ratingValue)

                // 地区 / 类型等副信息：独立一行
                val subtitle = wishSubtitle(item)
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 标记日期贴底，与海报下边缘对齐
                if (item.markedAt.isNotBlank()) {
                    Text(
                        text = "${item.markedAt} 标记想看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 豆瓣同款五星：10 分制折成 5 颗星，按任意比例填充（含半星），后接分数值。 */
@Composable
private fun DoubanRatingRow(
    rating: Double?,
    modifier: Modifier = Modifier,
) {
    val filled = (rating ?: 0.0) / 2.0
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(5) { index ->
            RatingStar(fill = (filled - index).coerceIn(0.0, 1.0).toFloat())
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = rating?.let { String.format(Locale.US, "%.1f", it) } ?: "暂无评分",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (rating != null) DoubanStarColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rating != null) {
            Text(
                text = " 分",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单颗星：底层灰色空心星 + 从左向右裁切出的橙色实心部分。 */
@Composable
private fun RatingStar(fill: Float) {
    val active = DoubanStarColor
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    Canvas(modifier = Modifier.size(15.dp)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val star = Path().apply {
            for (i in 0 until 10) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val r = if (i % 2 == 0) radius else radius * 0.45f
                val x = center.x + (cos(angle) * r).toFloat()
                val y = center.y + (sin(angle) * r).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(star, inactive)
        if (fill > 0f) {
            clipRect(right = size.width * fill) {
                drawPath(star, active)
            }
        }
    }
}

/** card_subtitle 常以年份开头（与徽标行的年份重复），去掉开头年份与分隔符。 */
private fun wishSubtitle(item: WishItem): String {
    val raw = item.subtitle.trim()
    if (raw.isEmpty()) return ""
    val year = item.year
    if (year.isBlank() || !raw.startsWith(year)) return raw
    return raw.removePrefix(year)
        .replace(Regex("""^[\s·/|，,-]+"""), "")
        .trim()
}
