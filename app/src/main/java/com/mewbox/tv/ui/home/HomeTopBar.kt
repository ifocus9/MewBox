package com.mewbox.tv.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.R
import com.mewbox.tv.ui.components.glassTopBarSurface
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * 首页顶部操作栏：
 * - 左侧 siteCapsule 站点选择胶囊（带圆角/阴影/Logo/展开箭头），自适应液态玻璃与拟态玻璃
 */
@Composable
fun HomeTopBar(
    currentSiteName: String,
    isRefreshingSubscription: Boolean,
    onSiteClick: () -> Unit,
    onSiteLongClick: () -> Unit,
    onLogoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val siteCapsuleContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 圆形站源 Logo 图标（对齐 MewPlayer 22dp 圆形裁切）
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onLogoClick() },
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = currentSiteName.ifBlank { "选择站点" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSiteClick,
                        onLongClick = onSiteLongClick,
                    ),
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (isRefreshingSubscription) {
                    CircularProgressIndicator(
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "切换站点",
                        tint = Color(0xFF555555),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .glassTopBarSurface(ContinuousCapsule, Color.White)
                .heightIn(min = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            siteCapsuleContent()
        }
    }
}

