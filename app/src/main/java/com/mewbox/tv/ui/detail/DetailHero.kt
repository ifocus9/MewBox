package com.mewbox.tv.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayDisabled
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.components.glassTopBarSurface
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private const val DetailTopScrimAlpha = 0.32f
private const val HERO_IMAGE_HEIGHT_RATIO = 0.50f
private const val HERO_ROLL_INTERVAL_MS = 5000L
private val HeroImageMinHeight = 280.dp
private val HeroImageMaxHeight = 460.dp
private val HeroContentHorizontalPadding = 16.dp
private val HeroContentTopPadding = 10.dp
private val HeroContentBottomPadding = 4.dp
private val HeroLogoBottomPadding = 16.dp
private val HeroPlayCapsuleHeight = 52.dp
private val HeroPlayCapsuleMinWidth = 180.dp
private val HeroPlayCapsuleHorizontalPadding = 32.dp
private val HeroLogoHeight = 84.dp
private val HeroRatingIconSize = 14.dp
private val HeroRatingStarColor = Color(0xFFFFC107)
private val HeroDescriptionSpacing = 14.dp
private const val HeroDescriptionAlpha = 0.82f
private const val HeroDescriptionCollapsedLines = 3
private const val MetaSeparator = " · "

private val DetailHeroFadeStops = arrayOf(
    0.75f to 0.05f,
    0.84f to 0.40f,
    0.90f to 0.72f,
    0.96f to 0.92f,
    1f to 1f,
)

private val DetailHeroFadeMaskBrush = Brush.verticalGradient(
    *DetailHeroFadeStops
        .map { (position, alpha) -> position to Color.White.copy(alpha = 1f - alpha) }
        .toTypedArray(),
)

/**
 * 顶部状态栏渐变遮罩（完全对齐 AVBox DetailTopScrim）
 */
@Composable
internal fun DetailTopScrim(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusTop + 24.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = DetailTopScrimAlpha * alpha.coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                ),
            ),
    )
}

/**
 * 页面上滑浏览信息区时顶部浮现的动态主题色渐变遮罩，避免滚动内容与悬浮工具栏生硬冲突
 */
@Composable
internal fun DetailScrollTopScrim(
    alphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val statusTop = with(LocalDensity.current) {
        WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp()
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val scrimBrush = remember(surfaceColor) {
        Brush.verticalGradient(
            0f to surfaceColor.copy(alpha = 0.82f),
            0.55f to surfaceColor.copy(alpha = 0.45f),
            1f to Color.Transparent,
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusTop + 58.dp)
            .graphicsLayer { alpha = alphaProvider() }
            .background(scrimBrush),
    )
}

private val DetailTopBarIconTint = Color.White.copy(alpha = 0.92f)

/**
 * 固定顶部工具栏：返回按钮与修正匹配按钮
 * 悬浮于详情页内容之上，页面滚动时保持固定不跟随滚动
 */
@Composable
fun DetailTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEditTmdb: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 返回按钮
        Box(
            modifier = Modifier
                .size(40.dp)
                .glassTopBarSurface(CircleShape, forceDark = true)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = DetailTopBarIconTint,
                modifier = Modifier.size(22.dp),
            )
        }

        // 修正匹配按钮
        if (onEditTmdb != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, forceDark = true)
                    .clip(CircleShape)
                    .clickable(onClick = onEditTmdb),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FindReplace,
                    contentDescription = "修正匹配",
                    tint = DetailTopBarIconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun rememberHeroImageHeight(): Dp {
    val configuration = LocalConfiguration.current
    return (configuration.screenHeightDp * HERO_IMAGE_HEIGHT_RATIO)
        .dp
        .coerceIn(HeroImageMinHeight, HeroImageMaxHeight)
}

/**
 * 大海报背景图层：
 * - 顶部锚定屏幕顶端，下拉时围绕顶部中心等比平滑放大，底部渐变融合边同步延展
 * - 上滑时解耦列表并以 0.30x 视差慢速上移 + Smoothstep 平滑淡出，与底层虚化背景自然融合
 */
@Composable
internal fun DetailHeroPosterLayer(
    vod: Vod,
    urls: List<String>,
    pagerState: PagerState,
    imageHeight: Dp,
    scrollOffsetPxProvider: () -> Float,
    pullDownPxProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(imageHeight)
            .graphicsLayer {
                val scrollPx = scrollOffsetPxProvider()
                val pullPx = pullDownPxProvider()
                val heroHeightPx = size.height
                val scale = computeHeroPosterScale(pullPx, heroHeightPx)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                translationY = computeHeroPosterTranslationY(scrollPx, pullPx, heroHeightPx)
                alpha = computeHeroPosterAlpha(scrollPx, pullPx, heroHeightPx)
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = DetailHeroFadeMaskBrush,
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            DetailHeroPosterMedia(
                vod = vod,
                urls = urls,
                pagerState = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )
        }
    }
}

/**
 * 大海报 Hero 前景内容（位于 LazyColumn 首项，与底层 [DetailHeroPosterLayer] 解耦联动）
 * - 顶部透明区域承接水平轮播手势并在底部居中展示 TMDB Logo 或片名
 * - ⭐ 评分 + 备注 · 年份 · 地区
 * - 可折叠简介
 * - 播放胶囊大按钮
 */
@Composable
fun DetailHero(
    vod: Vod,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
    resumeEpisodeText: String?,
    imageHeight: Dp,
    urls: List<String>,
    pagerState: PagerState,
    isAutoRollAllowed: () -> Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    hasPlayableRoutes: Boolean = true,
    onTitleLongClick: (() -> Unit)? = null,
) {
    // 评分信息
    val ratingText = tmdbDetail?.voteAverage
        ?.takeIf { it > 0.0 }
        ?.let { String.format(Locale.US, "%.1f", it) }

    // 元数据行 (备注 · 年份 · 地区)
    val metaTail = listOfNotNull(
        vod.vodRemarks.trim().takeIf { it.isNotEmpty() },
        vod.vodYear.takeIf { it.isNotEmpty() && it != "0" } ?: tmdbDetail?.year?.takeIf { it.isNotEmpty() },
        vod.vodArea.takeIf { it.isNotEmpty() },
    ).joinToString(MetaSeparator)

    // 简介信息
    val descriptionText = tmdbDetail?.overview?.takeIf { it.isNotBlank() }
        ?: vod.vodContent.takeIf { it.isNotBlank() }
    var descriptionExpanded by rememberSaveable(vod.vodId) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight),
        ) {
            // 仅由单个透明 HorizontalPager 接收横向手势并驱动 PagerState；
            // 背景层只读 pagerState 偏移做位移动画，避免双 HorizontalPager 共用同一 PagerState 冲突。
            if (urls.size > 1) {
                LaunchedEffect(pagerState, urls.size) {
                    while (true) {
                        delay(HERO_ROLL_INTERVAL_MS)
                        if (!pagerState.isScrollInProgress && isAutoRollAllowed()) {
                            val next = (pagerState.currentPage + 1) % urls.size
                            pagerState.animateScrollToPage(next)
                        }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            // 海报底部：TMDB Logo 或标题
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = HeroContentHorizontalPadding,
                        end = HeroContentHorizontalPadding,
                        bottom = HeroLogoBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DetailHeroTitle(
                    title = vod.vodName,
                    tmdbDetail = tmdbDetail,
                    tmdbImageBase = tmdbImageBase,
                    onLongClick = onTitleLongClick,
                )
            }
        }

        // Hero 元信息与主操作块
        DetailHeroInfoBlock(
            ratingText = ratingText,
            metaTail = metaTail,
            descriptionText = descriptionText,
            descriptionExpanded = descriptionExpanded,
            resumeEpisodeText = resumeEpisodeText,
            hasPlayableRoutes = hasPlayableRoutes,
            onToggleDescription = { descriptionExpanded = !descriptionExpanded },
            onPlay = onPlay,
            modifier = Modifier.padding(
                start = HeroContentHorizontalPadding,
                end = HeroContentHorizontalPadding,
                top = HeroContentTopPadding,
                bottom = HeroContentBottomPadding,
            ),
        )
    }
}

/**
 * 提取详情页所有可供轮播展示的高清海报/剧照列表
 */
internal fun detailPosterUrls(
    vod: Vod,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
    quickBackdrop: String? = null,
    quickPoster: String? = null,
): List<String> {
    val base = tmdbImageBase.trimEnd('/').ifEmpty { "https://image.tmdb.org/t/p" }
    val list = mutableListOf<String>()

    if (tmdbDetail != null) {
        // loaded 状态：横屏 Hero 以 backdrops 为主（按无字优先+评分降序最多取 4 张），再补少量 posters（最多取 2 张）
        tmdbDetail.images?.backdrops.orEmpty()
            .filter { !it.filePath.isNullOrEmpty() }
            .sortedWith(
                compareByDescending<com.mewbox.tv.api.tmdb.bean.TmdbImageItem> { it.iso639 == null }
                    .thenByDescending { it.voteAverage }
            )
            .take(4)
            .forEach { list.add("$base/w1280${it.filePath}") }

        if (list.isEmpty() && !tmdbDetail.backdropPath.isNullOrEmpty()) {
            list.add("$base/w1280${tmdbDetail.backdropPath}")
        }

        tmdbDetail.images?.posters.orEmpty()
            .filter { !it.filePath.isNullOrEmpty() }
            .sortedByDescending { it.voteAverage }
            .take(2)
            .forEach { list.add("$base/w780${it.filePath}") }

        if (list.isEmpty() && !tmdbDetail.posterPath.isNullOrEmpty()) {
            list.add("$base/w780${tmdbDetail.posterPath}")
        }
    } else {
        // quick 状态：优先使用横屏背景 quickBackdrop，为空时才降级 quickPoster，避免竖版海报被切断拉伸
        quickBackdrop?.let { list.add(it) } ?: quickPoster?.let { list.add(it) }
    }

    // 只有 TMDB 一张图都没给出时才回退站源海报。
    // 一旦拿到 TMDB 图，站源海报不再追加进轮播 —— 否则自动翻页会翻回站源图，
    // 视觉上就是「TMDB 匹配到了但海报没替换」。
    if (list.isEmpty() && vod.vodPic.isNotEmpty()) {
        list.add(vod.vodPic)
    }
    return list.distinct().take(5)
}

/**
 * 背景大海报图片渲染：通过读取 [pagerState] 偏移仅渲染当前与相邻页，实现零重组横滑切换
 */
@Composable
private fun DetailHeroPosterMedia(
    vod: Vod,
    urls: List<String>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    if (urls.isEmpty()) {
        PosterPlaceholder(
            title = vod.vodName,
            isHero = true,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (urls.size == 1) {
        HeroPosterImage(
            vodName = vod.vodName,
            targetUrl = urls[0],
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val current = pagerState.currentPage.coerceIn(0, urls.lastIndex)
    val startPage = (current - 1).coerceAtLeast(0)
    val endPage = (current + 1).coerceAtMost(urls.lastIndex)

    Box(modifier = modifier.fillMaxSize()) {
        for (page in startPage..endPage) {
            val url = urls[page]
            key(page, url) {
                HeroPosterImage(
                    vodName = vod.vodName,
                    targetUrl = url,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val pageOffset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                            translationX = pageOffset * size.width
                            alpha = if (abs(pageOffset) >= 1.05f) 0f else 1f
                        },
                )
            }
        }
    }
}

@Composable
private fun HeroPosterImage(
    vodName: String,
    targetUrl: String,
    modifier: Modifier = Modifier,
) {
    var currentUrl by remember(targetUrl) { mutableStateOf(targetUrl) }
    var hasFallenBack by remember(targetUrl) { mutableStateOf(false) }
    var isError by remember(targetUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (isError) {
            PosterPlaceholder(
                title = vodName,
                isHero = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = currentUrl,
                contentDescription = vodName,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        val fallback = TmdbClient.resolveFallbackImageUrl(currentUrl)
                        if (!hasFallenBack && fallback != null && fallback != currentUrl) {
                            hasFallenBack = true
                            currentUrl = fallback
                        } else {
                            isError = true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}


/**
 * 标题显示：TMDB Logo 优先，否则显示粗体片名（支持长按触发快搜换源）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailHeroTitle(
    title: String,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
    onLongClick: (() -> Unit)? = null,
) {
    val base = tmdbImageBase.trimEnd('/').ifEmpty { "https://image.tmdb.org/t/p" }
    val logoPath = tmdbDetail?.images?.logos?.firstOrNull()?.filePath

    val clickModifier = if (onLongClick != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = {},
                onLongClick = onLongClick,
            )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!logoPath.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(HeroLogoHeight),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = "$base/original$logoPath",
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight(800),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Hero 下方信息块：评分、折叠简介、大播放胶囊
 */
@Composable
private fun DetailHeroInfoBlock(
    ratingText: String?,
    metaTail: String,
    descriptionText: String?,
    descriptionExpanded: Boolean,
    resumeEpisodeText: String?,
    hasPlayableRoutes: Boolean,
    onToggleDescription: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clipToBounds(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 评分与元数据行
        if (ratingText != null || metaTail.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ratingText != null) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = HeroRatingStarColor,
                        modifier = Modifier.size(HeroRatingIconSize),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                if (metaTail.isNotEmpty()) {
                    Text(
                        text = if (ratingText != null) MetaSeparator + metaTail else metaTail,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }

        // 简介文本（点击展开/折叠）
        if (!descriptionText.isNullOrEmpty()) {
            Text(
                text = descriptionText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = HeroDescriptionAlpha),
                textAlign = TextAlign.Start,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else HeroDescriptionCollapsedLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HeroDescriptionSpacing)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleDescription,
                    ),
            )
        }

        if (hasPlayableRoutes) {
            // 播放胶囊大按钮（对齐 AVBox 经典 Button 胶囊）
            val playButtonText = if (!resumeEpisodeText.isNullOrEmpty()) "继续播放" else "播放"
            Button(
                onClick = onPlay,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .widthIn(min = HeroPlayCapsuleMinWidth)
                    .height(HeroPlayCapsuleHeight),
                shape = RoundedCornerShape(26.dp),
                contentPadding = PaddingValues(horizontal = HeroPlayCapsuleHorizontalPadding),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = playButtonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            // 未解析出线路或分集时，不展示播放按钮，改为提示暂无播放资源
            Row(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .widthIn(min = HeroPlayCapsuleMinWidth)
                    .height(HeroPlayCapsuleHeight)
                    .clip(RoundedCornerShape(26.dp))
                    .background(detailCardColor())
                    .padding(horizontal = HeroPlayCapsuleHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "暂无播放资源",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
