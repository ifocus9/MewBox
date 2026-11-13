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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.VodClass
import com.mewbox.tv.ui.theme.BlueTypeIndicator

/**
 * 首页分类横向导航条（分类项文字居中对齐指示条 + 弹性展开动效 + 点击当前分类平滑回顶 + 右侧筛选按钮）
 */
@Composable
fun HomeTabBar(
    classes: List<VodClass>,
    selectedClassId: String,
    tabListState: LazyListState,
    showFilterButton: Boolean,
    hasActiveFilters: Boolean,
    onClassClick: (classId: String, isAlreadySelected: Boolean) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = tabListState,
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(classes, key = { it.typeId }) { vodClass ->
                val isSelected = vodClass.typeId == selectedClassId

                val tabColor by animateColorAsState(
                    targetValue = if (isSelected) BlueTypeIndicator else Color(0xFF666666),
                    animationSpec = tween(durationMillis = 200),
                    label = "tabColor",
                )
                val tabScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1.0f,
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
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
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
                                        color = BlueTypeIndicator,
                                        shape = RoundedCornerShape(1.5.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }

        // 右侧筛选按钮（当前分类存在筛选项时才展示）
        if (showFilterButton) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp, end = 10.dp, bottom = 7.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onFilterClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = "筛选",
                    tint = if (hasActiveFilters) BlueTypeIndicator else Color(0xFF666666),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

