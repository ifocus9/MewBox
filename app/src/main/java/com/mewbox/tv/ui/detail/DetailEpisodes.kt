package com.mewbox.tv.ui.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.ui.components.EpisodePanelColor
import com.mewbox.tv.ui.components.EpisodePickerContent
import com.mewbox.tv.ui.components.rememberEpisodeDisplayNames

/**
 * 剧集选集栏（海报详情页）
 * 横排保留 AVBox 风格的玻璃卡片；「全部」打开的抽屉换成 MewPlayer 播放页的选集面板样式。
 */
@Composable
fun EpisodeRow(
    episodes: List<Episode>,
    selectedEpisode: Episode?,
    isReversed: Boolean,
    isCompactEpisodeTitle: Boolean,
    onToggleReverse: () -> Unit,
    onToggleCompact: () -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var episodeSheetOpen by rememberSaveable { mutableStateOf(false) }
    val displayEpisodes = if (isReversed) episodes.reversed() else episodes
    val displayNames = rememberEpisodeDisplayNames(episodes, isCompactEpisodeTitle)
    val orderedNames = if (isReversed) displayNames.reversed() else displayNames
    val listState = rememberLazyListState()

    // 选集变化时滚动到对应项
    LaunchedEffect(selectedEpisode, isReversed) {
        if (displayEpisodes.isEmpty()) return@LaunchedEffect
        val targetIndex = displayEpisodes.indexOf(selectedEpisode)
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(Icons.AutoMirrored.Filled.PlaylistPlay)
            Text(
                text = "剧集",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            PillAction(
                imageVector = if (isReversed) Icons.AutoMirrored.Filled.Sort else Icons.Default.SwapVert,
                text = if (isReversed) "正序" else "倒序",
                onClick = onToggleReverse,
            )
            Spacer(Modifier.width(6.dp))
            PillAction(
                imageVector = Icons.Default.GridView,
                text = "全部",
                onClick = { episodeSheetOpen = true },
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(displayEpisodes, key = { _, ep -> "${ep.index}_${ep.name}" }) { index, ep ->
                val selected = ep == selectedEpisode
                EpisodeNameCard(
                    name = orderedNames.getOrElse(index) { ep.name },
                    selected = selected,
                    onClick = { onEpisodeClick(ep) },
                )
            }
        }
    }

    if (episodeSheetOpen) {
        EpisodeSheet(
            episodes = episodes,
            displayNames = displayNames,
            selectedEpisode = selectedEpisode,
            isReversed = isReversed,
            isCompactEpisodeTitle = isCompactEpisodeTitle,
            onToggleReverse = onToggleReverse,
            onToggleCompact = onToggleCompact,
            onSelect = { ep ->
                episodeSheetOpen = false
                onEpisodeClick(ep)
            },
            onDismiss = { episodeSheetOpen = false },
        )
    }
}

/**
 * 剧集卡片（AVBox 经典玻璃质感圆角矩形）
 */
@Composable
private fun EpisodeNameCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(54.dp)
            .widthIn(min = 96.dp)
            .detailGlass(shape)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 操作胶囊按钮（正序/倒序、全部）
 */
@Composable
private fun PillAction(
    imageVector: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = detailCardColor(),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 全部剧集抽屉（对齐 MewPlayer 播放页选集面板：近黑半透明底 + 胶囊开关 + 分段标签 + 自适应网格）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSheet(
    episodes: List<Episode>,
    displayNames: List<String>,
    selectedEpisode: Episode?,
    isReversed: Boolean,
    isCompactEpisodeTitle: Boolean,
    onToggleReverse: () -> Unit,
    onToggleCompact: () -> Unit,
    onSelect: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = EpisodePanelColor,
        contentColor = Color.White,
        // MewPlayer 抽屉顶部 16dp 圆角
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.35f))
        },
    ) {
        EpisodePickerContent(
            episodes = episodes,
            displayNames = displayNames,
            currentEpisode = selectedEpisode,
            isReversed = isReversed,
            isCompact = isCompactEpisodeTitle,
            maxSpan = 4,
            onToggleCompact = onToggleCompact,
            onToggleReverse = onToggleReverse,
            onSelectEpisode = onSelect,
            horizontalPadding = 0.dp,
            bottomPadding = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp),
        )
    }
}
