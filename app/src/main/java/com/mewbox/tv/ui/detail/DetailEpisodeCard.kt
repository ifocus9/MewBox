package com.mewbox.tv.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.util.ResolvedEpisodeCard

private val EpisodeCardWidth = 164.dp
private val EpisodeCardImageShape = RoundedCornerShape(12.dp)
private val EpisodeCardOuterShape = RoundedCornerShape(
    topStart = 12.dp,
    topEnd = 12.dp,
    bottomStart = 6.dp,
    bottomEnd = 6.dp,
)
private val EpisodeBadgeShape = RoundedCornerShape(6.dp)
private val EpisodeToggleShape = RoundedCornerShape(20.dp)
private val EpisodePlayIconBg = Color.White.copy(alpha = 0.94f)
private val EpisodePlayIconTint = Color(0xFF16181D)
private val EpisodeUnselectedBadgeBg = Color.Black.copy(alpha = 0.62f)
private val EpisodeUnselectedBorderColor = Color.White.copy(alpha = 0.14f)

/**
 * TMDB 剧照选集卡片开关
 */
@Composable
internal fun TmdbEpisodeCardToggle(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = EpisodeToggleShape,
        color = if (enabled) primary.copy(alpha = 0.20f) else detailCardColor(),
        border = if (enabled) BorderStroke(1.dp, primary.copy(alpha = 0.45f)) else null,
        modifier = modifier
            .clip(EpisodeToggleShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = if (enabled) "关闭剧照卡片" else "开启剧照卡片",
                tint = if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 16:9 剧照选集卡片（TMDB 单集剧照 + 集数角标 + 选中播控标识 + 单集标题）
 */
@Composable
internal fun EpisodeStillCard(
    card: ResolvedEpisodeCard,
    fallbackBackdropUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(EpisodeCardWidth)
            .clip(EpisodeCardOuterShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(EpisodeCardImageShape)
                .background(detailCardColor())
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, EpisodeCardImageShape)
                    } else {
                        Modifier.border(0.5.dp, EpisodeUnselectedBorderColor, EpisodeCardImageShape)
                    },
                ),
        ) {
            EpisodeThumbnailImage(
                stillUrl = card.stillUrl,
                fallbackBackdropUrl = fallbackBackdropUrl,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
            )

            val badgeText = card.badgeText
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    shape = EpisodeBadgeShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else EpisodeUnselectedBadgeBg,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .defaultMinSize(minWidth = 22.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = EpisodePlayIconBg,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = EpisodePlayIconTint,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = card.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun EpisodeThumbnailImage(
    stillUrl: String?,
    fallbackBackdropUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backdropCandidates = remember(fallbackBackdropUrl) { buildUrlWithCdnFallback(fallbackBackdropUrl) }
    var backdropIndex by remember(backdropCandidates) { mutableIntStateOf(0) }
    val currentBackdropUrl = backdropCandidates.getOrNull(backdropIndex)

    val stillCandidates = remember(stillUrl) { buildUrlWithCdnFallback(stillUrl) }
    var stillIndex by remember(stillCandidates) { mutableIntStateOf(0) }
    var stillLoaded by remember(stillCandidates) { mutableStateOf(false) }
    val currentStillUrl = stillCandidates.getOrNull(stillIndex)

    Box(modifier = modifier.background(detailCardColor())) {
        // 剧照未加载完成前保留兜底背景图，防止分集接口到达或切换单集时闪黑底
        if (!stillLoaded && currentBackdropUrl != null) {
            AsyncImage(
                model = currentBackdropUrl,
                contentDescription = if (currentStillUrl == null) contentDescription else null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error && backdropIndex < backdropCandidates.lastIndex) {
                        backdropIndex += 1
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (currentStillUrl != null) {
            val request = remember(context, currentStillUrl) {
                ImageRequest.Builder(context)
                    .data(currentStillUrl)
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            if (state.result.request.data == currentStillUrl) {
                                stillLoaded = true
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            if (stillIndex < stillCandidates.lastIndex) {
                                stillIndex += 1
                            } else {
                                stillLoaded = false
                            }
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun buildUrlWithCdnFallback(rawUrl: String?): List<String> {
    val url = rawUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
    val fallback = TmdbClient.resolveFallbackImageUrl(url)
    return if (fallback != null && fallback != url) listOf(url, fallback) else listOf(url)
}
