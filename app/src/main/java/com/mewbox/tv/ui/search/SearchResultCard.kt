package com.mewbox.tv.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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

/**
 * 搜索结果单列横向图文卡片（严格对齐 MewPlayer adapter_search.xml 风格与尺寸参数）
 * - 高度 116dp，圆角 12dp，背景 #F8F9FB，边框 1dp #ECEEF2
 * - 左侧海报图 92dp x 116dp，左侧圆角 12dp，底部半透明黑底站点标签
 * - 右侧纵向详情：标题 14.5sp 加粗最多 3 行，底部实底橙色 #FA8C16 备注胶囊
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
        color = Color(0xFFF8F9FB),
        border = BorderStroke(1.dp, Color(0xFFECEEF2)),
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
                    .background(Color(0xFFE5E7EB)),
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

                // 底部站点名称半透明标签（对齐 MewPlayer shape_vod_site_list_bar）
                if (vod.siteName.isNotBlank() || vod.siteKey.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(bottomStart = 12.dp))
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
                        )
                    }
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
                // 片名（14.5sp bold，最多3行）
                Text(
                    text = vod.vodName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp,
                    ),
                    color = Color(0xFF1F2329),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                // 底部备注标签（对齐 MewPlayer shape_search_remark: #FA8C16，圆角 5dp，加粗白字 11.5sp）
                if (vod.vodRemarks.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFFA8C16))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp),
                    ) {
                        Text(
                            text = vod.vodRemarks,
                            color = Color.White,
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
