package com.mewbox.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.transition.Transition
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.theme.ImagePalette
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.ui.theme.PosterSeedMemory

/**
 * 影视卡片（2:3 高清海报 + 细腻描边 + 轻渐变角标）
 * - 标准 2:3 比例高清海报，10dp 圆角 + 0.5dp 细描边（浅色模式白底封面边界依然利落）
 * - 禁用滚动期 crossfade、禁用高频按压弹性重组状态机，原生 Ripple 反馈，确保列表满帧流畅滚动
 * - 首页（不展示站点条时）：底部轻渐变遮罩 + 右下角纯白备注；搜索页（展示站点条时）：右上角柔和半透明胶囊
 * - 底部片名左对齐单行排布，13sp Medium，超出省略
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VodCard(
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showSite: Boolean = false,
) {
    val context = LocalContext.current
    val loadedImageRef = remember(vod.vodPic) { arrayOfNulls<Image>(1) }
    val posterShape = RoundedCornerShape(10.dp)

    val imageRequest = remember(context, vod.vodPic) {
        ImageRequest.Builder(context)
            .data(vod.vodPic)
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(posterShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = {
                    if (vod.vodPic.isNotEmpty() && !PosterSeedMemory.has(vod.vodPic)) {
                        loadedImageRef[0]?.let { img ->
                            ImagePalette.seedOf(img)?.let { seed ->
                                PosterSeedMemory.put(vod.vodPic, seed)
                            }
                        }
                    }
                    onClick()
                },
                onLongClick = onLongClick,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        var imageLoadFailed by remember(vod.vodPic) { mutableStateOf(vod.vodPic.isEmpty()) }

        // 海报卡片（10dp 圆角 + 0.5dp 细描边，仅加载失败展示占位卡片）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(posterShape)
                .background(MewBoxTheme.colors.posterPlaceholderBg)
                .border(0.5.dp, MewBoxTheme.colors.subtleBorder, posterShape),
        ) {
            if (imageLoadFailed) {
                PosterPlaceholder(
                    title = vod.vodName,
                    isHero = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = vod.vodName,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                loadedImageRef[0] = state.result.image
                            }
                            is AsyncImagePainter.State.Error -> {
                                imageLoadFailed = true
                            }
                            else -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (vod.vodRemarks.isNotBlank()) {
                if (!showSite) {
                    // 首页：底部柔和暗渐变 + 右下角纯白轻字，避免左上/右上黑块遮挡封面主体
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.32f)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.68f),
                                ),
                            ),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Text(
                            text = vod.vodRemarks,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color.White.copy(alpha = 0.95f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        )
                    }
                } else {
                    // 搜索双列（底部已占站点条）：右上角柔和半透明胶囊
                    Surface(
                        shape = ContinuousCapsule,
                        color = Color(0x8A000000),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 5.dp, top = 5.dp)
                            .widthIn(max = 88.dp),
                    ) {
                        Text(
                            text = vod.vodRemarks,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // 左下角紧凑站点胶囊（仅搜索聚合"全部"时展示，避免整行黑条遮挡封面）
            if (showSite && (vod.siteName.isNotBlank() || vod.siteKey.isNotBlank())) {
                Surface(
                    shape = ContinuousCapsule,
                    color = Color(0x8A000000),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 5.dp, bottom = 5.dp)
                        .widthIn(max = 88.dp),
                ) {
                    Text(
                        text = vod.siteName.ifBlank { vod.siteKey },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = Color.White.copy(alpha = 0.95f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 片名（左对齐，13sp Medium，单行超出省略）
        Text(
            text = vod.vodName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        )
    }
}
