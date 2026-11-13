package com.mewbox.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.ui.theme.BluePrimary
import com.mewbox.tv.util.EpisodeTitleCompact
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme

/**
 * 选集面板样式（完全对齐 MewPlayer 播放页右侧选集面板 EpisodeListDialog）：
 * 近黑半透明底、白色粗体标题、半透明深灰细边胶囊按钮、文字分段标签（1-20 / 21-40）+ 按标题长度自适应列数的网格。
 * 选中项文字与边框变为主色（系统主色，不联动海报主题色），背景色保持不变。
 *
 * 详情页（海报页）底部抽屉与全屏右侧面板共用这一套内容，只在外层决定容器形态。
 */

/** 面板底色：MewPlayer shape_episode_panel_bg #E6101118 */
val EpisodePanelColor = Color(0xE6101118)

/** 胶囊按钮：selector_player_panel_group_tab */
internal val PillColor = Color(0x22FFFFFF)
internal val PillBorderColor = Color(0x33FFFFFF)

internal val PanelTextPrimary = Color.White

/** 选集抽屉固定采用系统主色，不联动海报主题色 */
internal val PanelAccent: Color = BluePrimary

/** 次要文字（共N条 / 全N集）：@color/selector_player_panel_tab_text */
internal val PanelTextMeta = Color(0x99FFFFFF)

internal val PanelShape = RoundedCornerShape(6.dp)
internal val PillShape = RoundedCornerShape(6.dp)

/** 长标题阈值，与 MewPlayer EpisodeAdapter.LONG_TITLE_LENGTH 一致：到这一档就切成两行、两列。 */
private const val LONG_TITLE_LENGTH = 12
private const val EPISODE_ITEM_HEIGHT = 40

/**
 * 分段窗口（1-20 / 21-40 …）。start/end 是「展示顺序」下的下标。
 * 分组策略与 MewPlayer EpisodeGroupAdapter 一致：集数越多每组越大。
 */
data class EpisodeGroup(val label: String, val start: Int, val end: Int)

fun episodeGroupSize(size: Int): Int = when {
    size <= 60 -> 20
    size > 2500 -> 300
    size > 1500 -> 200
    size > 1000 -> 150
    size > 500 -> 100
    size > 300 -> 50
    else -> 40
}

/** [selectedIndex] 为展示顺序下的当前集下标，用于把当前集所在分段标记为选中。 */
fun buildEpisodeGroups(size: Int, selectedIndex: Int, reversed: Boolean): List<EpisodeGroup> {
    if (size <= 0) return emptyList()
    val groupSize = episodeGroupSize(size)
    val count = (size + groupSize - 1) / groupSize
    val groups = ArrayList<EpisodeGroup>(count)
    for (i in 0 until count) {
        val start = i * groupSize
        val end = minOf(start + groupSize, size)
        // 倒序时列表从最后一集开始排，标签跟着从 N 往回数
        val labelStart = if (reversed) size - start else start + 1
        val labelEnd = if (reversed) size - end + 1 else end
        val label = if (labelStart == labelEnd) labelStart.toString() else "$labelStart-$labelEnd"
        groups.add(EpisodeGroup(label, start, end))
    }
    return groups
}

/**
 * 选集面板内容：头部（选集 / 全N集 / 精简 / 倒序）+ 分段标签 + 可滚动网格。
 *
 * @param episodes 原始顺序的剧集列表
 * @param displayNames 与 episodes 同序的展示名（已按精简开关处理）
 * @param maxSpan 网格最大列数：详情页抽屉 4，全屏面板 5
 */
@Composable
fun EpisodePickerContent(
    episodes: List<Episode>,
    displayNames: List<String>,
    currentEpisode: Episode?,
    isReversed: Boolean,
    isCompact: Boolean,
    maxSpan: Int,
    onToggleCompact: () -> Unit,
    onToggleReverse: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    /** 网格左右内边距：详情页抽屉 16dp，全屏面板随面板自身 12dp 内边距收进 */
    horizontalPadding: Dp = 16.dp,
    bottomPadding: Dp = 0.dp,
) {
    val orderedEpisodes = remember(episodes, isReversed) {
        if (isReversed) episodes.asReversed() else episodes
    }
    val orderedNames = remember(displayNames, isReversed) {
        if (isReversed) displayNames.asReversed() else displayNames
    }
    val selectedIndex = remember(orderedEpisodes, currentEpisode) {
        orderedEpisodes.indexOf(currentEpisode).coerceAtLeast(0)
    }
    val groups = remember(orderedEpisodes.size, selectedIndex, isReversed) {
        buildEpisodeGroups(orderedEpisodes.size, selectedIndex, isReversed)
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val maxNameLength = remember(orderedNames) { orderedNames.maxOfOrNull { it.length } ?: 0 }
    val gridPadding = remember(horizontalPadding, bottomPadding) {
        PaddingValues(start = horizontalPadding, end = horizontalPadding, bottom = bottomPadding)
    }

    Column(modifier = modifier) {
        EpisodePickerHeader(
            count = orderedEpisodes.size,
            isCompact = isCompact,
            isReversed = isReversed,
            onToggleCompact = onToggleCompact,
            onToggleReverse = onToggleReverse,
        )

        if (groups.size > 1) {
            Spacer(Modifier.height(10.dp))
            EpisodeGroupTabs(
                groups = groups,
                gridState = gridState,
                horizontalPadding = horizontalPadding,
                onSelect = { group -> scope.launch { gridState.scrollToItem(group.start) } },
            )
        }

        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val span = remember(maxNameLength, maxWidth, maxSpan) {
                resolveSpan(maxNameLength, maxWidth, maxSpan)
            }

            // 打开时把当前集所在行滚到列表上方（MewPlayer 是留出约 1/3 视口，这里对齐到行首即可）
            LaunchedEffect(selectedIndex, span) {
                if (orderedEpisodes.isEmpty()) return@LaunchedEffect
                gridState.scrollToItem(selectedIndex - selectedIndex % span)
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(span),
                contentPadding = gridPadding,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(
                    items = orderedEpisodes,
                    key = { index, ep -> "picker_${ep.index}_${ep.name}_$index" },
                ) { index, ep ->
                    EpisodeGridCell(
                        name = orderedNames.getOrElse(index) { ep.name },
                        selected = ep == currentEpisode,
                        multiLine = maxNameLength >= LONG_TITLE_LENGTH,
                        onClick = { onSelectEpisode(ep) },
                    )
                }
            }
        }
    }
}

/**
 * 按「精简」开关计算与 [episodes] 同序的展示名。
 * 精简需要整列表参与（求公共前后缀、找变化的集数），所以统一在这里做缓存计算，
 * 详情页横排、底部抽屉、全屏面板都复用它，保证三处名字始终一致。
 */
@Composable
fun rememberEpisodeDisplayNames(episodes: List<Episode>, compact: Boolean): List<String> =
    remember(episodes, compact) { EpisodeTitleCompact.displayNames(episodes, compact) }

/** 按标题长度决定列数：纯集数可以密一点，长标题退回两列两行。 */
private fun resolveSpan(maxNameLength: Int, availableWidth: Dp, maxSpan: Int): Int {
    if (maxSpan <= 1) return 1
    if (maxNameLength >= LONG_TITLE_LENGTH) return minOf(2, maxSpan)
    val ideal = when {
        maxNameLength >= 10 -> 130
        maxNameLength >= 7 -> 104
        else -> 80
    }
    val span = (availableWidth.value / ideal).toInt()
    return span.coerceIn(2, maxSpan)
}

/** 头部：选集 + 全N集 + 精简 / 倒序（对齐 EpisodeListDialog 的 header） */
@Composable
private fun EpisodePickerHeader(
    count: Int,
    isCompact: Boolean,
    isReversed: Boolean,
    onToggleCompact: () -> Unit,
    onToggleReverse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "选集",
            color = PanelTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "全${count}集",
            color = PanelTextMeta,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        EpisodePickerPill(
            text = "精简",
            selected = isCompact,
            onClick = onToggleCompact,
        )
        Spacer(Modifier.width(5.dp))
        EpisodePickerPill(
            text = "倒序",
            selected = isReversed,
            icon = Icons.Default.SwapVert,
            onClick = onToggleReverse,
        )
    }
}

/** 胶囊按钮：未选中半透明深灰细边，选中时文字与边框变为主色，背景色不变 */
@Composable
private fun EpisodePickerPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val accent = PanelAccent
    Surface(
        shape = PillShape,
        color = PillColor,
        border = BorderStroke(1.dp, if (selected) accent else PillBorderColor),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accent else PanelTextPrimary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = text,
                color = if (selected) accent else PanelTextPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** 分段标签：与集数卡片同款胶囊，选中时文字与边框变为主色，背景色不变，随网格滚动自动切换 */
@Composable
private fun EpisodeGroupTabs(
    groups: List<EpisodeGroup>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    horizontalPadding: Dp,
    onSelect: (EpisodeGroup) -> Unit,
) {
    val accent = PanelAccent
    // 滚到底时用最后一段，保证最后一段能被选中；未完成布局时 canScrollForward 也是 false，这里用 firstVisible 兜底
    val firstVisible = gridState.firstVisibleItemIndex
    val atEnd = !gridState.canScrollForward && firstVisible > 0
    val activeIndex = remember(groups, firstVisible, atEnd) {
        if (atEnd && groups.isNotEmpty()) {
            groups.lastIndex
        } else {
            groups.indexOfLast { it.start <= firstVisible }.coerceAtLeast(0)
        }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        itemsIndexed(groups) { index, group ->
            val selected = index == activeIndex
            Surface(
                shape = PillShape,
                color = PillColor,
                border = BorderStroke(1.dp, if (selected) accent else PillBorderColor),
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onSelect(group) },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = group.label,
                        color = if (selected) accent else PanelTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 剧集卡片：40dp 高、6dp 圆角，选中时文字与边框变为主色，背景色不变（保持半透明深灰底） */
@Composable
private fun EpisodeGridCell(
    name: String,
    selected: Boolean,
    multiLine: Boolean,
    onClick: () -> Unit,
) {
    val accent = PanelAccent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPISODE_ITEM_HEIGHT.dp)
            .clip(PanelShape)
            .background(PillColor)
            .border(1.dp, if (selected) accent else PillBorderColor, PanelShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            color = if (selected) accent else PanelTextPrimary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = if (multiLine) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}
