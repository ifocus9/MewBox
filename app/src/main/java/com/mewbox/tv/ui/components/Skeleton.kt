package com.mewbox.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import kotlin.math.hypot

/**
 * 移植自 AVBox 的 Shimmer 骨架屏渐变扫光效果：
 * 1. 高光采用 onSurface.copy(alpha = 0.18f)：浅色主题下压暗、深色主题下提亮，双主题对比度完美呈现。
 * 2. 扫光位移采用 travel = 对角线² / 宽：使得竖向 2:3 海报高光平滑进出，消除循环重启跳变闪回。
 */
fun Modifier.shimmer(
    base: Color? = null,
    highlight: Color? = null,
): Modifier = composed {
    val baseColor = base ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val shineColor = highlight ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    drawWithCache {
        val startX = shimmerStartX(size.width, size.height, progress)
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, shineColor, baseColor),
            start = Offset(startX, 0f),
            end = Offset(startX + size.width, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}

internal fun shimmerStartX(width: Float, height: Float, progress: Float): Float {
    val diagonal = hypot(width, height)
    if (width <= 0f || diagonal <= 0f) return 0f
    val travel = diagonal * diagonal / width
    return -travel + 2f * travel * progress
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    baseColor: Color? = null,
) {
    val background = baseColor ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .shimmer(base = background),
    )
}

/**
 * 单个影视海报骨架项：严格预留卡片海报高度与片名行高，数据到达时零跳动
 */
@Composable
fun VodGridSkeleton(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 0.72f,
    cornerRadius: Dp = 8.dp,
    titleLine: Dp = 14.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(cornerRadius)),
            shape = RoundedCornerShape(cornerRadius),
        )
        Spacer(modifier = Modifier.height(6.dp))
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(titleLine)
                .clip(RoundedCornerShape(4.dp)),
            shape = RoundedCornerShape(4.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * 影视网格骨架屏（对齐 AVBox HomeGridSkeletonCount = 18）
 */
@Composable
fun VodGridSkeletonGroup(
    modifier: Modifier = Modifier,
    count: Int = 18,
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 96.dp),
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(count) {
            VodGridSkeleton()
        }
    }
}

/**
 * 影视单列横向卡片骨架项（严格对齐 SearchResultCard 尺寸与布局）
 */
@Composable
fun VodListSkeleton(
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF8F9FB),
        border = BorderStroke(1.dp, Color(0xFFECEEF2)),
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            SkeletonBox(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBox(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp),
                    )
                    SkeletonBox(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
                SkeletonBox(
                    modifier = Modifier
                        .width(64.dp)
                        .height(18.dp)
                        .clip(ContinuousCapsule),
                    shape = ContinuousCapsule,
                )
            }
        }
    }
}

/**
 * 影视单列列表骨架屏
 */
@Composable
fun VodListSkeletonGroup(
    modifier: Modifier = Modifier,
    count: Int = 10,
    contentPadding: PaddingValues = PaddingValues(top = 6.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(count) {
            VodListSkeleton()
        }
    }
}
