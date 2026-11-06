package com.mewbox.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.Vod

/**
 * 详情页主体滚动内容（完全对齐 AVBox DetailContent）
 */
@Composable
fun DetailContent(
    vod: Vod,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
    flags: List<Flag>,
    currentFlag: Flag?,
    episodes: List<Episode>,
    currentEpisode: Episode?,
    isEpisodeReversed: Boolean,
    isCompactEpisodeTitle: Boolean,
    changeSources: List<Vod>,
    isSearchingChangeSources: Boolean = false,
    currentSiteKey: String,
    currentSourceName: String,
    resumeEpisodeText: String?,
    onPlay: () -> Unit,
    onToggleReverse: () -> Unit,
    onToggleCompact: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onSelectFlag: (Flag) -> Unit,
    onSelectSource: (Vod) -> Unit,
    onTitleLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp + navBottom),
    ) {
        // 1. Hero 区域（大海报、标题、评分、折叠简介、播放胶囊大按钮）
        item(key = "hero") {
            DetailHero(
                vod = vod,
                tmdbDetail = tmdbDetail,
                tmdbImageBase = tmdbImageBase,
                resumeEpisodeText = resumeEpisodeText,
                onPlay = onPlay,
                onTitleLongClick = onTitleLongClick,
            )
        }

        // 2. 剧集选集栏（正序/倒序、全部、横向集数卡片）
        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(
                    episodes = episodes,
                    selectedEpisode = currentEpisode,
                    isReversed = isEpisodeReversed,
                    isCompactEpisodeTitle = isCompactEpisodeTitle,
                    onToggleReverse = onToggleReverse,
                    onToggleCompact = onToggleCompact,
                    onEpisodeClick = onSelectEpisode,
                )
            }
        }

        // 3. 线路切换栏（线路名称、集数）
        if (flags.isNotEmpty()) {
            item(key = "flags") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    ) {
                        SectionTitleIcon(Icons.AutoMirrored.Filled.AltRoute)
                        Text(
                            text = "线路",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = currentSourceName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .widthIn(max = 180.dp),
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(flags, key = { index, f -> "${index}_${f.flag}" }) { _, flag ->
                            LineCard(
                                name = flag.flag,
                                count = flag.episodes.size,
                                selected = flag == currentFlag,
                                onClick = { onSelectFlag(flag) },
                            )
                        }
                    }
                }
            }
        }

        // 4. 换源栏（展示多站同名源或正在检索换源）
        if (changeSources.isNotEmpty() || isSearchingChangeSources) {
            item(key = "sources") {
                SourceSection(
                    sources = changeSources,
                    isSearching = isSearchingChangeSources,
                    currentSiteKey = currentSiteKey,
                    onSelectSource = onSelectSource,
                )
            }
        }

        // 5. TMDB 演职员
        val cast = tmdbDetail?.credits?.cast.orEmpty()
        if (cast.isNotEmpty()) {
            item(key = "tmdb_cast") {
                TmdbCastSection(cast = cast, imageBase = tmdbImageBase)
            }
        }

        // 6. TMDB 类型标签
        val genres = tmdbDetail?.genres?.map { it.name }.orEmpty()
        if (genres.isNotEmpty()) {
            item(key = "tmdb_genres") {
                TmdbGenreSection(genres = genres)
            }
        }
    }
}
