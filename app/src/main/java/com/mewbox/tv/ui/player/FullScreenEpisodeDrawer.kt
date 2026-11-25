package com.mewbox.tv.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.ui.components.EpisodePickerContent
import com.mewbox.tv.ui.components.PanelAccent
import com.mewbox.tv.ui.components.PanelTextMeta
import com.mewbox.tv.ui.components.PanelTextPrimary
import com.mewbox.tv.ui.components.PillColor
import com.mewbox.tv.ui.components.PillShape
import com.mewbox.tv.ui.components.rememberEpisodeDisplayNames

/**
 * 全屏播放时的选集与线路自适应面板。
 *
 * 外壳由 [PlayerDrawerShell] 统一提供（横屏右侧悬浮岛卡片 / 竖屏底部弹出 Sheet，
 * 点空白/返回键关闭、遥控器焦点、safeInsets）；本组件只负责内容：
 * - 多集剧集：顶部多线路选择（多于1条线路时展示）+ 下方剧集列表；
 * - 单集影片（仅换线路场景）：隐藏单集选集区，直接以网格平铺展示所有可用线路，点选后自动收起。
 */
@Composable
fun FullScreenEpisodeDrawer(
    episodes: List<Episode>,
    currentEpisode: Episode?,
    isReversed: Boolean,
    isCompactEpisodeTitle: Boolean,
    onToggleSort: () -> Unit,
    onToggleCompact: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    flags: List<Flag> = emptyList(),
    currentFlag: Flag? = null,
    onSelectFlag: (Flag) -> Unit = {},
    isLandscape: Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp },
) {
    val displayNames = rememberEpisodeDisplayNames(episodes, isCompactEpisodeTitle)
    val hasMultiEpisodes = episodes.size > 1

    PlayerDrawerShell(
        onDismiss = onClose,
        isLandscape = isLandscape,
        modifier = modifier,
    ) {
        if (hasMultiEpisodes) {
            if (flags.size > 1) {
                DrawerFlagSection(
                    flags = flags,
                    currentFlag = currentFlag,
                    onSelectFlag = onSelectFlag,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            EpisodePickerContent(
                episodes = episodes,
                displayNames = displayNames,
                currentEpisode = currentEpisode,
                isReversed = isReversed,
                isCompact = isCompactEpisodeTitle,
                onToggleCompact = onToggleCompact,
                onToggleReverse = onToggleSort,
                onSelectEpisode = onSelectEpisode,
                horizontalPadding = 0.dp,
                bottomPadding = 8.dp,
                modifier = Modifier.weight(1f),
            )
        } else if (flags.isNotEmpty()) {
            DrawerFlagGridSection(
                flags = flags,
                currentFlag = currentFlag,
                onSelectFlag = { flag ->
                    onSelectFlag(flag)
                    onClose()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 抽屉顶部线路切换栏（多集剧集模式下横向滚动）
 */
@Composable
private fun DrawerFlagSection(
    flags: List<Flag>,
    currentFlag: Flag?,
    onSelectFlag: (Flag) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentFlag) {
        val targetIndex = flags.indexOfFirst { it == currentFlag || it.flag == currentFlag?.flag }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "线路",
                color = PanelTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "共${flags.size}条",
                color = PanelTextMeta,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        ) {
            itemsIndexed(
                items = flags,
                key = { index, flag -> "drawer_flag_${index}_${flag.flag}" },
            ) { _, flag ->
                val isSelected = flag == currentFlag || flag.flag == currentFlag?.flag
                DrawerFlagPill(
                    name = flag.flag,
                    isSelected = isSelected,
                    onClick = { onSelectFlag(flag) },
                )
            }
        }
    }
}

/**
 * 单集影片模式下的线路网格面板（不展示冗余的单集选集区）
 */
@Composable
private fun DrawerFlagGridSection(
    flags: List<Flag>,
    currentFlag: Flag?,
    onSelectFlag: (Flag) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "线路",
                color = PanelTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "共${flags.size}条",
                color = PanelTextMeta,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            gridItemsIndexed(
                items = flags,
                key = { index, flag -> "drawer_flag_grid_${index}_${flag.flag}" },
            ) { _, flag ->
                val isSelected = flag == currentFlag || flag.flag == currentFlag?.flag
                DrawerFlagPill(
                    name = flag.flag,
                    isSelected = isSelected,
                    onClick = { onSelectFlag(flag) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerFlagPill(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.height(34.dp),
) {
    Surface(
        shape = PillShape,
        color = if (isSelected) PanelAccent.copy(alpha = 0.28f) else PillColor,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                color = if (isSelected) PanelAccent else PanelTextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
