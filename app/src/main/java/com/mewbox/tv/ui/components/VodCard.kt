package com.mewbox.tv.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.transition.Transition
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.theme.ImagePalette
import com.mewbox.tv.ui.theme.OrangeBadge
import com.mewbox.tv.ui.theme.PosterSeedMemory

/**
 * 影视卡片（严格对齐 MewPlayer adapter_vod_rect.xml 样式与 AVBox VodPoster 滚动性能）
 * - 0.72 比例高清海报，8dp 圆角
 * - 缩略图禁用滚动期 crossfade 与每卡片 shimmer InfiniteTransition，仅在点击卡片时按需提取海报主题色，确保列表满帧流畅滚动
 * - 右上角橙色标签（#FA8C16，圆角 5dp，加粗白字 10.5sp）
 * - 底部片名居中排布，13.5sp，单行超出省略
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

    // 按压缩放回弹反馈（仅按压状态切换时触发重组，绘制走 graphicsLayer，不影响滚动性能）
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "cardPressScale",
    )

    val imageRequest = remember(context, vod.vodPic) {
        ImageRequest.Builder(context)
            .data(vod.vodPic)
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = interactionSource,
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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var imageLoadFailed by remember(vod.vodPic) { mutableStateOf(vod.vodPic.isEmpty()) }

        // 海报卡片（单次裁切 + 轻量静态占位底色，仅加载失败展示占位卡片）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x0D000000)),
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

            // 右上角：橙色备注标签（对齐 MewPlayer shape_vod_score_badge: #FA8C16，圆角 5dp）
            if (vod.vodRemarks.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = OrangeBadge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 5.dp, top = 5.dp),
                ) {
                    Text(
                        text = vod.vodRemarks,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 5.5.dp, vertical = 2.dp),
                    )
                }
            }

            // 底部站点名称半透明标签（对齐 MewPlayer adapter_vod_rect.xml site & shape_vod_site_bar: #99000000，圆角下 8dp）
            if (showSite && (vod.siteName.isNotBlank() || vod.siteKey.isNotBlank())) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = vod.siteName.ifBlank { vod.siteKey },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // 片名（对齐 MewPlayer: 居中，13.5sp，单行，超出省略）
        Text(
            text = vod.vodName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 3.dp, start = 2.dp, end = 2.dp),
        )
    }
}
