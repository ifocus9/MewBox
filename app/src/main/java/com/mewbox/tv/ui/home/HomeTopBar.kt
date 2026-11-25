package com.mewbox.tv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.R
import com.mewbox.tv.ui.components.glassTopBarSurface
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 首页顶部操作栏：
 * - 左侧站点选择胶囊（Logo 点击切订阅，站点名+箭头整块点击换源/长按刷新订阅），自适应液态玻璃与拟态玻璃
 * - 右侧独立圆形玻璃筛选按钮（当前分类存在筛选项时展示，已激活筛选时显示数字角标）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTopBar(
    currentSiteName: String,
    isRefreshingSubscription: Boolean,
    isSiteDialogOpen: Boolean,
    showFilterButton: Boolean,
    activeFilterCount: Int,
    hasActiveFilters: Boolean,
    onSiteClick: () -> Unit,
    onSiteLongClick: () -> Unit,
    onLogoClick: () -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(min = 36.dp)
                .glassTopBarSurface(ContinuousCapsule, MewBoxTheme.colors.floatingCardBg),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = "切换订阅",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onLogoClick,
                        ),
                )

                Row(
                    modifier = Modifier
                        .clip(ContinuousCapsule)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSiteClick,
                            onLongClick = onSiteLongClick,
                        )
                        .padding(start = 6.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentSiteName.ifBlank { "选择站点" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    if (isRefreshingSubscription) {
                        CircularProgressIndicator(
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (isSiteDialogOpen) 180f else 0f,
                            animationSpec = tween(durationMillis = 200),
                            label = "siteArrowRotation",
                        )
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "切换站点",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                        )
                    }
                }
            }
        }

        if (showFilterButton) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .glassTopBarSurface(CircleShape, MewBoxTheme.colors.floatingCardBg),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .clickable(onClick = onFilterClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "筛选",
                            tint = if (hasActiveFilters) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (activeFilterCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                            .background(MaterialTheme.colorScheme.primary, ContinuousCapsule)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (activeFilterCount > 9) "9+" else activeFilterCount.toString(),
                            fontSize = 9.5.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                } else if (hasActiveFilters) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 1.dp, y = (-1).dp)
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

