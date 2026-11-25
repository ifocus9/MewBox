package com.mewbox.tv.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mewbox.tv.api.tmdb.bean.TmdbCastMember
import com.mewbox.tv.bean.Vod

private const val DetailCardAlpha = 0.5f
private const val DetailSelectedCardAlpha = 0.18f
private const val DetailSelectedBorderAlpha = 0.45f

internal val DetailItemHeight = 70.dp
internal val DetailItemMinWidth = 160.dp
internal val DetailItemWidth = 160.dp
private val DetailCardShape = RoundedCornerShape(16.dp)
private val CastAvatarSize = 72.dp
private val CastItemWidth = 84.dp

@Composable
internal fun detailCardColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = DetailCardAlpha)

@Composable
internal fun detailSelectedCardColor(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = DetailSelectedCardAlpha)

@Composable
internal fun detailSelectedCardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = DetailSelectedBorderAlpha))

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun DetailItemCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = DetailCardShape,
        color = if (selected) detailSelectedCardColor() else detailCardColor(),
        border = if (selected) detailSelectedCardBorder() else null,
        modifier = modifier
            .height(DetailItemHeight)
            .defaultMinSize(minWidth = DetailItemMinWidth)
            .clip(DetailCardShape)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * 换源区（对齐 AVBox SourceSection）
 */
@Composable
internal fun SourceSection(
    sources: List<Vod>,
    isSearching: Boolean,
    currentSiteKey: String,
    onSelectSource: (Vod) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty() && !isSearching) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
            SectionTitleIcon(Icons.Rounded.SwapHoriz)
            Text(
                text = "换源",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (isSearching) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "检索中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sources.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sources, key = { "${it.siteKey}_${it.vodId}" }) { item ->
                    val selected = item.siteKey == currentSiteKey
                    DetailItemCard(
                        selected = selected,
                        onClick = { onSelectSource(item) },
                        modifier = Modifier.width(DetailItemWidth),
                    ) {
                        Text(
                            text = item.siteName.ifEmpty { item.siteKey },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.vodRemarks.isNotEmpty()) {
                            Text(
                                text = item.vodRemarks,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * TMDB 演职员区（对齐 AVBox TmdbCastSection）
 */
@Composable
internal fun TmdbCastSection(
    cast: List<TmdbCastMember>,
    imageBase: String = "",
    modifier: Modifier = Modifier,
) {
    if (cast.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
            SectionTitleIcon(Icons.Rounded.Group)
            Text(
                text = "演职员",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cast.take(15), key = { "${it.id}_${it.name}" }) { member ->
                TmdbCastItem(member, imageBase)
            }
        }
    }
}

@Composable
private fun TmdbCastItem(member: TmdbCastMember, imageBase: String) {
    val avatarUrl = if (!member.profilePath.isNullOrEmpty()) {
        val base = imageBase.trimEnd('/').ifEmpty { "https://image.tmdb.org/t/p" }
        "$base/w185${member.profilePath}"
    } else ""

    Column(
        modifier = Modifier.width(CastItemWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CastAvatarSize)
                .clip(CircleShape)
                .background(detailCardColor()),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(CastAvatarSize),
                )
            } else {
                Text(
                    text = member.name.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (member.character.isNotEmpty()) {
            Text(
                text = member.character,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * TMDB 影片类型标签区（对齐 AVBox TmdbGenreSection）
 */
@Composable
internal fun TmdbGenreSection(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(Icons.Rounded.Category)
            Text(
                text = "类型",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(genres) { genre ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = detailCardColor(),
                ) {
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
