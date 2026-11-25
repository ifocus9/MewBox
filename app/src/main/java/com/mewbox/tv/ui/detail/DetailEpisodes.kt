package com.mewbox.tv.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbEpisode
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.ui.components.EPISODE_SELECTED_BG_ALPHA
import com.mewbox.tv.ui.components.EpisodePickerColors
import com.mewbox.tv.ui.components.EpisodePickerContent
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.rememberEpisodeDisplayNames
import com.mewbox.tv.util.TmdbEpisodeMatcher

/**
 * 剧集选集栏（海报详情页）
 * 匹配到 TMDB 时默认展示 16:9 剧照卡片（电影复用横版背景图，剧集展示单集剧照，可通过剧照开关切回经典玻璃胶囊）；
 * 线路切换按钮紧跟在「剧集」标题后，点击弹出与选集抽屉同风格的线路底部抽屉；
 * 「全部」打开的抽屉保持选集面板样式。
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
    vodName: String = "",
    vodPic: String = "",
    flags: List<Flag> = emptyList(),
    currentFlag: Flag? = null,
    currentSourceName: String = "",
    onSelectFlag: (Flag) -> Unit = {},
    tmdbDetail: TmdbDetail? = null,
    tmdbImageBase: String = "",
    quickBackdrop: String? = null,
    tmdbEpisodesBySeason: Map<Int, List<TmdbEpisode>> = emptyMap(),
    isTmdbEpisodeCardEnabled: Boolean = true,
    onToggleTmdbEpisodeCards: () -> Unit = {},
) {
    var episodeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var flagSheetOpen by rememberSaveable { mutableStateOf(false) }
    val flagName = currentFlag?.flag.orEmpty()
    val displayEpisodes = remember(episodes, isReversed) {
        if (isReversed) episodes.asReversed() else episodes
    }
    val displayNames = rememberEpisodeDisplayNames(episodes, isCompactEpisodeTitle)
    val orderedNames = remember(displayNames, isReversed) {
        if (isReversed) displayNames.asReversed() else displayNames
    }

    val canUseTmdbEpisodeCards = tmdbDetail != null
    val showTmdbEpisodeCards = canUseTmdbEpisodeCards && isTmdbEpisodeCardEnabled

    val fallbackBackdropUrl = remember(tmdbDetail, tmdbImageBase, quickBackdrop, vodPic) {
        if (canUseTmdbEpisodeCards) {
            TmdbEpisodeMatcher.resolveFallbackBackdropUrl(
                tmdbDetail = tmdbDetail,
                tmdbImageBase = tmdbImageBase,
                quickBackdrop = quickBackdrop,
                vodPic = vodPic,
            )
        } else null
    }

    val resolvedCards = remember(
        episodes,
        displayNames,
        vodName,
        flagName,
        tmdbDetail,
        tmdbEpisodesBySeason,
        tmdbImageBase,
        showTmdbEpisodeCards,
    ) {
        if (!showTmdbEpisodeCards) {
            emptyList()
        } else {
            TmdbEpisodeMatcher.resolveCards(
                episodes = episodes,
                fallbackDisplayNames = displayNames,
                vodName = vodName,
                flagName = flagName,
                tmdbDetail = tmdbDetail,
                tmdbEpisodesBySeason = tmdbEpisodesBySeason,
                imageBase = tmdbImageBase,
            )
        }
    }
    val orderedCards = remember(resolvedCards, isReversed) {
        if (isReversed) resolvedCards.asReversed() else resolvedCards
    }

    val listState = rememberLazyListState()

    // 仅在选集或正倒序变化时滚动定位，避免异步加载 TMDB 详情时打断用户当前横滑
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
            SectionTitleIcon(Icons.Rounded.VideoLibrary)
            Text(
                text = "剧集",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (flags.isNotEmpty()) {
                    FlagPillAction(
                        text = currentFlag?.flag?.ifBlank { null }
                            ?: flags.firstOrNull()?.flag?.ifBlank { null }
                            ?: "线路",
                        onClick = { flagSheetOpen = true },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (episodes.isNotEmpty()) {
                    if (flags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                    }
                    if (canUseTmdbEpisodeCards) {
                        TmdbEpisodeCardToggle(
                            enabled = isTmdbEpisodeCardEnabled,
                            onClick = onToggleTmdbEpisodeCards,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    PillAction(
                        imageVector = Icons.Rounded.SwapVert,
                        contentDescription = if (isReversed) "切换为正序" else "切换为倒序",
                        selected = isReversed,
                        onClick = onToggleReverse,
                    )
                    Spacer(Modifier.width(6.dp))
                    PillAction(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = "全部剧集",
                        onClick = { episodeSheetOpen = true },
                    )
                }
            }
        }

        if (episodes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (showTmdbEpisodeCards) 12.dp else 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                itemsIndexed(
                    items = displayEpisodes,
                    key = { _, ep -> "${ep.index}_${ep.name}" },
                    contentType = { _, _ -> if (showTmdbEpisodeCards) "card" else "pill" },
                ) { index, ep ->
                    val selected = ep == selectedEpisode
                    if (showTmdbEpisodeCards && index < orderedCards.size) {
                        EpisodeStillCard(
                            card = orderedCards[index],
                            fallbackBackdropUrl = fallbackBackdropUrl,
                            selected = selected,
                            onClick = { onEpisodeClick(ep) },
                        )
                    } else {
                        EpisodeNameCard(
                            name = orderedNames.getOrElse(index) { ep.name },
                            selected = selected,
                            onClick = { onEpisodeClick(ep) },
                        )
                    }
                }
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

    if (flagSheetOpen && flags.isNotEmpty()) {
        FlagSheet(
            flags = flags,
            selectedFlag = currentFlag,
            currentSourceName = currentSourceName,
            onSelect = { flag ->
                flagSheetOpen = false
                onSelectFlag(flag)
            },
            onDismiss = { flagSheetOpen = false },
        )
    }
}

/**
 * 剧集卡片（扁平半透明圆角矩形）
 */
@Composable
private fun EpisodeNameCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) detailSelectedCardColor() else detailCardColor(),
        border = if (selected) detailSelectedCardBorder() else null,
        modifier = modifier
            .height(54.dp)
            .widthIn(min = 96.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
}

/**
 * 线路切换胶囊按钮（与右侧操作按钮同排，仅在线路名过长时省略文本而不挤压右侧按钮）
 */
@Composable
private fun FlagPillAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(20.dp)
    Surface(
        shape = pillShape,
        color = detailCardColor(),
        modifier = modifier
            .clip(pillShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "切换线路",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 纯图标操作胶囊按钮（正序/倒序、全部剧集，对齐 [TmdbEpisodeCardToggle] 视觉风格）
 */
@Composable
private fun PillAction(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val pillShape = RoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = pillShape,
        color = if (selected) primary.copy(alpha = 0.20f) else detailCardColor(),
        border = if (selected) BorderStroke(1.dp, primary.copy(alpha = 0.45f)) else null,
        modifier = Modifier
            .clip(pillShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = if (selected) primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 线路选择抽屉（样式与选中态完全对齐 [EpisodeSheet]）
 */
@Composable
private fun FlagSheet(
    flags: List<Flag>,
    selectedFlag: Flag?,
    currentSourceName: String,
    onSelect: (Flag) -> Unit,
    onDismiss: () -> Unit,
) {
    val itemShape = RoundedCornerShape(6.dp)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    val hasExactMatch = remember(flags, selectedFlag) {
        selectedFlag != null && flags.any { it == selectedFlag }
    }
    val selectedIndex = remember(flags, selectedFlag, hasExactMatch) {
        if (selectedFlag == null) {
            0
        } else {
            flags.indexOfFirst { flag ->
                if (hasExactMatch) flag == selectedFlag else flag.flag == selectedFlag.flag
            }.coerceAtLeast(0)
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (flags.isNotEmpty()) {
            listState.scrollToItem(selectedIndex)
        }
    }

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val posterPrimary = MaterialTheme.colorScheme.primary
        val textPrimary = MaterialTheme.colorScheme.onSurface
        val textMeta = MaterialTheme.colorScheme.onSurfaceVariant
        val pillColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val selectedBg = posterPrimary.copy(alpha = EPISODE_SELECTED_BG_ALPHA)

        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "线路",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "共${flags.size}条",
                    color = textMeta,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (currentSourceName.isNotBlank()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = currentSourceName,
                        color = textMeta,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .widthIn(max = 180.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                itemsIndexed(
                    items = flags,
                    key = { index, flag -> "flag_sheet_${index}_${flag.flag}" },
                ) { _, flag ->
                    val selected = selectedFlag != null && if (hasExactMatch) {
                        flag == selectedFlag
                    } else {
                        flag.flag == selectedFlag.flag
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(itemShape)
                            .background(if (selected) selectedBg else pillColor)
                            .clickable { onSelect(flag) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = flag.flag,
                            color = if (selected) posterPrimary else textPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (flag.episodes.isNotEmpty()) {
                            Text(
                                text = " · ${flag.episodes.size}集",
                                color = if (selected) posterPrimary.copy(alpha = 0.8f) else textMeta,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
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
    MewSheetShell(
        onDismiss = onDismiss,
        // 与 TMDB 抽屉同为 Light：跟随详情页主题 surface 背景、onSurface 内容色
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val posterPrimary = MaterialTheme.colorScheme.primary
        EpisodePickerContent(
            episodes = episodes,
            displayNames = displayNames,
            currentEpisode = selectedEpisode,
            isReversed = isReversed,
            isCompact = isCompactEpisodeTitle,
            onToggleCompact = onToggleCompact,
            onToggleReverse = onToggleReverse,
            onSelectEpisode = onSelect,
            horizontalPadding = 0.dp,
            bottomPadding = 20.dp,
            modifier = focusModifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp),
            // 对齐 TMDB 抽屉的 M3 语义色：胶囊底 surfaceContainerHighest、选中色随主题 primary（海报派生色）
            // 选中态与播放页统一为半透明主色底 + 主色文字，主色取海报派生 primary
            colors = EpisodePickerColors(
                pill = MaterialTheme.colorScheme.surfaceContainerHighest,
                pillBorder = MaterialTheme.colorScheme.outlineVariant,
                textPrimary = MaterialTheme.colorScheme.onSurface,
                textMeta = MaterialTheme.colorScheme.onSurfaceVariant,
                accent = posterPrimary,
                selectedBg = posterPrimary.copy(alpha = EPISODE_SELECTED_BG_ALPHA),
                selectedText = posterPrimary,
            ),
        )
    }
}
