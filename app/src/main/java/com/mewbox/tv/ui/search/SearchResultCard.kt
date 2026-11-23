package com.mewbox.tv.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.PosterPlaceholder
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 搜索结果单列横向图文卡片（严格对齐 MewPlayer adapter_search.xml 风格与尺寸参数）
 * - 高度 116dp，圆角 12dp，背景 #F8F9FB，边框 1dp #ECEEF2
 * - 左侧海报图 92dp x 116dp，左侧圆角 12dp
 * - 右侧纵向详情：标题 14.5sp 加粗最多 2 行、灰色站点名称 12sp、底部备注胶囊
 */
@Composable
fun SearchResultCard(
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MewBoxTheme.colors.searchCardBg,
        border = BorderStroke(1.dp, MewBoxTheme.colors.cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧海报图（92dp 宽度，填满 116dp 高度）
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                var loadFailed by remember(vod.vodPic) { mutableStateOf(vod.vodPic.isBlank()) }
                if (loadFailed) {
                    PosterPlaceholder(
                        title = vod.vodName,
                        isHero = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = vod.vodPic,
                        contentDescription = vod.vodName,
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) {
                                loadFailed = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 右侧详情信息区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    // 片名（14.5sp bold，最多2行）
                    Text(
                        text = vod.vodName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // 站点名称（无背景，弱于片名的灰色展示）
                    if (vod.siteName.isNotBlank() || vod.siteKey.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = vod.siteName.ifBlank { vod.siteKey },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 底部备注标签（容器主色文字 + 柔和主色底，圆角 5dp，11.5sp）
                if (vod.vodRemarks.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(MewBoxTheme.colors.primarySoftBg)
                            .padding(horizontal = 6.dp, vertical = 2.5.dp),
                    ) {
                        Text(
                            text = vod.vodRemarks,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
