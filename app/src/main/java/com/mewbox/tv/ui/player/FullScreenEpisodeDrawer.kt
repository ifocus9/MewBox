package com.mewbox.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.ui.components.EpisodePanelColor
import com.mewbox.tv.ui.components.EpisodePickerContent
import com.mewbox.tv.ui.components.PanelAccent
import com.mewbox.tv.ui.components.PanelTextMeta
import com.mewbox.tv.ui.components.PanelTextPrimary
import com.mewbox.tv.ui.components.PillBorderColor
import com.mewbox.tv.ui.components.PillColor
import com.mewbox.tv.ui.components.PillShape
import com.mewbox.tv.ui.components.rememberEpisodeDisplayNames

/**
 * 全屏播放时的右侧选集与线路面板。
 *
 * 版式完全对齐 MewPlayer 播放页的 EpisodeListDialog：
 * - 贴右边、无圆角、近黑半透明底（#E6101118），内容四周 12dp 内边距；
 * - 宽度 = min(560dp, max(360dp, 屏宽 × 44%))；
 * - 顶部多线路选择（多于1条线路时展示）；
 * - 选集头部「选集 + 全N集 + 精简 / 倒序」，下面是 1-20 / 21-40 分段标签；
 * - 剧集网格按标题长度自适应 2~5 列、40dp 高、当前集蓝底白边；
 * - 打开时当前集滚到列表上方，点空白处或返回键关闭。
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
    playUrls: List<PlayUrl> = emptyList(),
    selectedPlayUrl: PlayUrl? = null,
    onSelectPlayUrl: (PlayUrl) -> Unit = {},
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val insets = rememberPlayerSafeInsets()
    val panelWidth = minOf(560.dp, maxOf(360.dp, (screenWidthDp * 0.44f).dp) + insets.horizontal)
    val displayNames = rememberEpisodeDisplayNames(episodes, isCompactEpisodeTitle)
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxSize()) {
        // 面板外区域点击关闭（MewPlayer 靠 overlay 空白点击退出，不额外放关闭按钮）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClose,
                ),
        )

        Surface(
            color = EpisodePanelColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                // 面板本身吞掉点击，避免点面板空白处穿透到遮罩把抽屉关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        end = insets.horizontal + 12.dp,
                        top = insets.top + 12.dp,
                        bottom = insets.bottom + 12.dp,
                    ),
            ) {
                if (flags.size > 1) {
                    DrawerFlagSection(
                        flags = flags,
                        currentFlag = currentFlag,
                        onSelectFlag = onSelectFlag,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (playUrls.size > 1 || (playUrls.size == 1 && playUrls.first().name != "默认")) {
                    DrawerQualitySection(
                        playUrls = playUrls,
                        selectedPlayUrl = selectedPlayUrl,
                        onSelectPlayUrl = onSelectPlayUrl,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                EpisodePickerContent(
                    episodes = episodes,
                    displayNames = displayNames,
                    currentEpisode = currentEpisode,
                    isReversed = isReversed,
                    isCompact = isCompactEpisodeTitle,
                    maxSpan = 5,
                    onToggleCompact = onToggleCompact,
                    onToggleReverse = onToggleSort,
                    onSelectEpisode = onSelectEpisode,
                    horizontalPadding = 0.dp,
                    bottomPadding = 12.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 抽屉顶部线路切换栏
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
                Surface(
                    shape = PillShape,
                    color = PillColor,
                    border = BorderStroke(1.dp, if (isSelected) PanelAccent else PillBorderColor),
                    modifier = Modifier
                        .height(34.dp)
                        .clickable { onSelectFlag(flag) },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = flag.flag,
                            color = if (isSelected) PanelAccent else PanelTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 抽屉顶部画质切换栏
 */
@Composable
private fun DrawerQualitySection(
    playUrls: List<PlayUrl>,
    selectedPlayUrl: PlayUrl?,
    onSelectPlayUrl: (PlayUrl) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playUrls.isEmpty()) return
    val listState = rememberLazyListState()

    LaunchedEffect(selectedPlayUrl) {
        val targetIndex = playUrls.indexOfFirst { it == selectedPlayUrl || it.url == selectedPlayUrl?.url }
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
                text = "画质",
                color = PanelTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            selectedPlayUrl?.name?.takeIf { it.isNotBlank() }?.let { curName ->
                Text(
                    text = curName,
                    color = PanelTextMeta,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
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
                items = playUrls,
                key = { index, item -> "drawer_quality_${index}_${item.name}_${item.url}" },
            ) { _, item ->
                val isSelected = item == selectedPlayUrl || item.url == selectedPlayUrl?.url
                Surface(
                    shape = PillShape,
                    color = PillColor,
                    border = BorderStroke(1.dp, if (isSelected) PanelAccent else PillBorderColor),
                    modifier = Modifier
                        .height(34.dp)
                        .clickable { onSelectPlayUrl(item) },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.name,
                            color = if (isSelected) PanelAccent else PanelTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
