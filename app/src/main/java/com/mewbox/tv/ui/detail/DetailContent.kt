package com.mewbox.tv.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbEpisode
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.Vod

private val TypeSeparators = Regex("[,，、/|;；\\s]+")

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
    quickBackdrop: String? = null,
    quickPoster: String? = null,
    tmdbEpisodesBySeason: Map<Int, List<TmdbEpisode>> = emptyMap(),
    isTmdbEpisodeCardEnabled: Boolean = true,
    onToggleTmdbEpisodeCards: () -> Unit = {},
    onTitleLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val imageHeight = rememberHeroImageHeight()
    val imageHeightPx = with(density) { imageHeight.toPx() }
    val urls = remember(tmdbDetail, tmdbImageBase, vod.vodPic, quickBackdrop, quickPoster) {
        detailPosterUrls(vod, tmdbDetail, tmdbImageBase, quickBackdrop, quickPoster)
    }
    val pagerState = rememberPagerState(pageCount = { urls.size.coerceAtLeast(1) })
    val heroScrollState = rememberDetailHeroScrollState { imageHeightPx * HeroMaxPullHeightRatio }
    // 将下拉偏移施加在各列表项上而非 LazyColumn 本身，避免拖拽过程中改变手势宿主坐标导致抖动
    val itemPullModifier = Modifier.graphicsLayer { translationY = heroScrollState.pullDownPx }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .nestedScroll(heroScrollState.nestedScrollConnection),
    ) {
        // 1. 背景大海报层：顶部锚定下拉等比放大，上滑 0.30x 视差上移并平滑淡出
        DetailHeroPosterLayer(
            vod = vod,
            urls = urls,
            pagerState = pagerState,
            imageHeight = imageHeight,
            scrollOffsetPxProvider = {
                resolveHeroScrollOffsetPx(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    imageHeightPx = imageHeightPx,
                )
            },
            pullDownPxProvider = { heroScrollState.pullDownPx },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 2. 前景滚动列表：禁用原生 Overscroll 拉伸以保持前景内容 1:1 刚体平移
        LazyColumn(
            state = listState,
            overscrollEffect = null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp + navBottom),
        ) {
            // 1. Hero 区域（透明手势轮播占位与标题、评分、折叠简介、播放胶囊大按钮）
            item(key = "hero") {
                DetailHero(
                    vod = vod,
                    tmdbDetail = tmdbDetail,
                    tmdbImageBase = tmdbImageBase,
                    resumeEpisodeText = resumeEpisodeText,
                    imageHeight = imageHeight,
                    urls = urls,
                    pagerState = pagerState,
                    isAutoRollAllowed = {
                        heroScrollState.pullDownPx <= 0.5f &&
                            listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset < imageHeightPx * 0.5f
                    },
                    hasPlayableRoutes = flags.any { it.episodes.isNotEmpty() },
                    onPlay = onPlay,
                    onTitleLongClick = onTitleLongClick,
                    modifier = itemPullModifier,
                )
            }

            // 2. 剧集选集栏（标题旁附带线路抽屉按钮，以及正序/倒序、全部、横向集数卡片）
            if (episodes.isNotEmpty() || flags.isNotEmpty()) {
                item(key = "episodes") {
                    EpisodeRow(
                        episodes = episodes,
                        selectedEpisode = currentEpisode,
                        isReversed = isEpisodeReversed,
                        isCompactEpisodeTitle = isCompactEpisodeTitle,
                        onToggleReverse = onToggleReverse,
                        onToggleCompact = onToggleCompact,
                        onEpisodeClick = onSelectEpisode,
                        vodName = vod.vodName,
                        vodPic = vod.vodPic,
                        flags = flags,
                        currentFlag = currentFlag,
                        currentSourceName = currentSourceName,
                        onSelectFlag = onSelectFlag,
                        tmdbDetail = tmdbDetail,
                        tmdbImageBase = tmdbImageBase,
                        quickBackdrop = quickBackdrop,
                        tmdbEpisodesBySeason = tmdbEpisodesBySeason,
                        isTmdbEpisodeCardEnabled = isTmdbEpisodeCardEnabled,
                        onToggleTmdbEpisodeCards = onToggleTmdbEpisodeCards,
                        modifier = itemPullModifier,
                    )
                }
            }

            // 3. 换源栏（展示多站同名源或正在检索换源）
            if (changeSources.isNotEmpty() || isSearchingChangeSources) {
                item(key = "sources") {
                    SourceSection(
                        sources = changeSources,
                        isSearching = isSearchingChangeSources,
                        currentSiteKey = currentSiteKey,
                        onSelectSource = onSelectSource,
                        modifier = itemPullModifier,
                    )
                }
            }

            // 4. TMDB 演职员
            val cast = tmdbDetail?.credits?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                item(key = "tmdb_cast") {
                    TmdbCastSection(
                        cast = cast,
                        imageBase = tmdbImageBase,
                        modifier = itemPullModifier,
                    )
                }
            }

            // 5. 类型标签（优先 TMDB genres，未匹配到时回退站源 typeName）
            val genres = tmdbDetail?.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: vod.typeName.split(TypeSeparators).filter { it.isNotBlank() }.distinct()
            if (genres.isNotEmpty()) {
                item(key = "tmdb_genres") {
                    TmdbGenreSection(
                        genres = genres,
                        modifier = itemPullModifier,
                    )
                }
            }
        }

        // 3. 顶部动态主题色渐变遮罩（位于列表之上、DetailTopBar 之下；静止与下拉态透明）
        DetailScrollTopScrim(
            alphaProvider = {
                computeTopScrollScrimAlpha(
                    scrollOffsetPx = resolveHeroScrollOffsetPx(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        imageHeightPx = imageHeightPx,
                    ),
                    pullDownPx = heroScrollState.pullDownPx,
                    imageHeightPx = imageHeightPx,
                )
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
