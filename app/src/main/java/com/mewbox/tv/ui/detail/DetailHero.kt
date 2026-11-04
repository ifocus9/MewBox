package com.mewbox.tv.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.LocalTopBarGlassBackdrop
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.components.glassTopBarSurface
import com.mewbox.tv.ui.theme.LiquidGlassDefaults
import kotlinx.coroutines.delay
import java.util.Locale

private const val DetailTopScrimAlpha = 0.32f
private const val HERO_IMAGE_HEIGHT_RATIO = 0.50f
private const val HERO_ROLL_INTERVAL_MS = 5000L
private const val HERO_ROLL_ANIM_MS = 300
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
private val TypeSeparators = Regex("[,，、/|;；\\s]+")

private val DetailHeroFadeStops = arrayOf(
    0.75f to 0.05f,
    0.84f to 0.40f,
    0.90f to 0.72f,
    0.96f to 0.92f,
    1f to 1f,
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
                .detailGlass(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }

        // 修正匹配按钮
        if (onEditTmdb != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .detailGlass(CircleShape)
                    .clickable(onClick = onEditTmdb),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "修正匹配",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 大海报 Hero 区域（完全对齐 AVBox DetailHero）
 * - 大海报淡化下边缘融合
 * - TMDB Logo 或片名
 * - ⭐ 评分 + 年份 · 地区 · 分类 · 备注
 * - 可折叠简介
 * - 播放胶囊大按钮
 */
@Composable
fun DetailHero(
    vod: Vod,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
    resumeEpisodeText: String?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val imageHeight = (configuration.screenHeightDp * HERO_IMAGE_HEIGHT_RATIO)
        .dp
        .coerceIn(HeroImageMinHeight, HeroImageMaxHeight)

    // 收集所有可用图片路径（TMDB 背景与海报优先，末尾拼接站点海报）
    val urls = remember(tmdbDetail, vod.vodPic) {
        detailPosterUrls(vod, tmdbDetail, tmdbImageBase)
    }

    val pagerState = rememberPagerState(pageCount = { urls.size.coerceAtLeast(1) })

    // 自动轮播（若存在多张高清海报/剧照且用户未处于手势拖拽中）
    if (urls.size > 1) {
        LaunchedEffect(pagerState, urls.size) {
            while (true) {
                delay(HERO_ROLL_INTERVAL_MS)
                if (!pagerState.isScrollInProgress) {
                    val next = (pagerState.currentPage + 1) % urls.size
                    pagerState.animateScrollToPage(next)
                }
            }
        }
    }

    // 评分信息
    val ratingText = tmdbDetail?.voteAverage
        ?.takeIf { it > 0.0 }
        ?.let { String.format(Locale.US, "%.1f", it) }

    // 元数据行 (年份 · 地区 · 分类 · 备注)
    val rawTypes = tmdbDetail?.genres?.map { it.name }?.takeIf { it.isNotEmpty() }
        ?: vod.typeName.split(TypeSeparators).filter { it.isNotBlank() }
    val types = rawTypes.joinToString(MetaSeparator)

    val metaTail = listOfNotNull(
        vod.vodRemarks.trim().takeIf { it.isNotEmpty() },
        vod.vodYear.takeIf { it.isNotEmpty() && it != "0" } ?: tmdbDetail?.year?.takeIf { it.isNotEmpty() },
        vod.vodArea.takeIf { it.isNotEmpty() },
        types.takeIf { it.isNotEmpty() },
    ).joinToString(MetaSeparator)

    // 简介信息
    val descriptionText = tmdbDetail?.overview?.takeIf { it.isNotBlank() }
        ?: vod.vodContent.takeIf { it.isNotBlank() }
    var descriptionExpanded by rememberSaveable(vod.vodId) { mutableStateOf(false) }
    val glassBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .clipToBounds(),
        ) {
            // 1. 大海报图片层（下边缘平滑透明度衰减，支持手势滑动切换）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                *DetailHeroFadeStops
                                    .map { (position, alpha) -> position to Color.White.copy(alpha = 1f - alpha) }
                                    .toTypedArray(),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                    DetailHeroPoster(
                        vod = vod,
                        urls = urls,
                        pagerState = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 2. 海报底部：TMDB Logo 或 标题
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
                )
            }
        }

        // 4. Hero 元信息与主操作块
        DetailHeroInfoBlock(
            ratingText = ratingText,
            metaTail = metaTail,
            descriptionText = descriptionText,
            descriptionExpanded = descriptionExpanded,
            resumeEpisodeText = resumeEpisodeText,
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
): List<String> {
    val base = tmdbImageBase.trimEnd('/').ifEmpty { "https://image.tmdb.org/t/p" }
    val list = mutableListOf<String>()
    tmdbDetail?.images?.backdrops?.mapNotNull { it.filePath }?.forEach {
        list.add("$base/w1280$it")
    }
    if (list.isEmpty() && !tmdbDetail?.backdropPath.isNullOrEmpty()) {
        list.add("$base/w1280${tmdbDetail.backdropPath}")
    }
    tmdbDetail?.images?.posters?.mapNotNull { it.filePath }?.forEach {
        list.add("$base/w780$it")
    }
    if (list.isEmpty() && !tmdbDetail?.posterPath.isNullOrEmpty()) {
        list.add("$base/w780${tmdbDetail.posterPath}")
    }
    if (list.isEmpty() && vod.vodPic.isNotEmpty()) {
        list.add(vod.vodPic)
    }
    if (vod.vodPic.isNotEmpty() && !list.contains(vod.vodPic)) {
        list.add(vod.vodPic)
    }
    return list.distinct()
}

/**
 * 海报手势轮播/背景图（完全对齐 AVBox DetailHeroPoster，支持横向手势拖拽滑动切换）
 */
@Composable
private fun DetailHeroPoster(
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
        val targetUrl = urls[0]
        var isError by remember(targetUrl) { mutableStateOf(false) }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            if (isError) {
                PosterPlaceholder(
                    title = vod.vodName,
                    isHero = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = targetUrl,
                    contentDescription = vod.vodName,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            isError = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = modifier.fillMaxSize(),
    ) { page ->
        val targetUrl = urls[page]
        var isError by remember(targetUrl) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            if (isError) {
                PosterPlaceholder(
                    title = vod.vodName,
                    isHero = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = targetUrl,
                    contentDescription = vod.vodName,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Error) {
                            isError = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}


/**
 * 标题显示：TMDB Logo 优先，否则显示粗体片名
 */
@Composable
private fun DetailHeroTitle(
    title: String,
    tmdbDetail: TmdbDetail?,
    tmdbImageBase: String,
) {
    val base = tmdbImageBase.trimEnd('/').ifEmpty { "https://image.tmdb.org/t/p" }
    val logoPath = tmdbDetail?.images?.logos?.firstOrNull()?.filePath

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
            modifier = Modifier.fillMaxWidth(),
        )
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
                        imageVector = Icons.Default.Star,
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleDescription),
            )
        }

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
                imageVector = Icons.Default.PlayArrow,
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
    }
}
