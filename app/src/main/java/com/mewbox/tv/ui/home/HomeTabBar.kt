package com.mewbox.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.VodClass

/**
 * 首页分类横向导航条（独占全宽 + 条件边缘渐隐 + 选中态弹性微缩放与居中红条指示器）
 */
@Composable
fun HomeTabBar(
    classes: List<VodClass>,
    selectedClassId: String,
    tabListState: LazyListState,
    onClassClick: (classId: String, isAlreadySelected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fadeLeft by remember(tabListState) { derivedStateOf { tabListState.canScrollBackward } }
    val fadeRight by remember(tabListState) { derivedStateOf { tabListState.canScrollForward } }

    LazyRow(
        state = tabListState,
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val leftFadePx = 14.dp.toPx()
                val rightFadePx = 20.dp.toPx()
                if (fadeLeft && leftFadePx > 0f && size.width > leftFadePx) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = leftFadePx,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(leftFadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (fadeRight && rightFadePx > 0f && size.width > rightFadePx) {
                    val startX = size.width - rightFadePx
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = startX,
                            endX = size.width,
                        ),
                        topLeft = Offset(startX, 0f),
                        size = Size(rightFadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        items(classes, key = { it.typeId }) { vodClass ->
            val isSelected = vodClass.typeId == selectedClassId

            val primaryColor = MaterialTheme.colorScheme.primary
            val tabColor by animateColorAsState(
                targetValue = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 200),
                label = "tabColor",
            )
            val tabScale by animateFloatAsState(
                targetValue = if (isSelected) 1.04f else 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "tabScale",
            )
            val indicatorWidth by animateDpAsState(
                targetValue = if (isSelected) 18.dp else 0.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "indicatorWidth",
            )
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(durationMillis = 160),
                label = "indicatorAlpha",
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onClassClick(vodClass.typeId, isSelected)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = vodClass.typeName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = tabColor,
                    modifier = Modifier.graphicsLayer {
                        scaleX = tabScale
                        scaleY = tabScale
                    },
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 居中指示条容器：与分类文字严格居中对齐，支持自中心向两侧弹性伸展动画
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (indicatorWidth > 0.dp && indicatorAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .width(indicatorWidth)
                                .height(3.dp)
                                .graphicsLayer { alpha = indicatorAlpha }
                                .background(
                                    color = primaryColor,
                                    shape = RoundedCornerShape(1.5.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

