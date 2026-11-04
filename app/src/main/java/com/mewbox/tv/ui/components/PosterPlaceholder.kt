package com.mewbox.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 经典影视质感海报占位配色集（深邃沉浸、克制优雅）
 */
private val PosterCinemaPalettes = listOf(
    listOf(Color(0xFF222938), Color(0xFF131722)), // 深邃星夜
    listOf(Color(0xFF282A36), Color(0xFF16181F)), // 极光石墨
    listOf(Color(0xFF2B2236), Color(0xFF171220)), // 幽邃靛紫
    listOf(Color(0xFF1B2A29), Color(0xFF0F1A19)), // 松柏暗青
    listOf(Color(0xFF242C38), Color(0xFF141922)), // 暮色幽蓝
    listOf(Color(0xFF2F2524), Color(0xFF1A1312)), // 沉香玄木
    listOf(Color(0xFF252932), Color(0xFF15181E)), // 暗灰青黛
    listOf(Color(0xFF1F2B33), Color(0xFF11191F)), // 深海沉蓝
)

/**
 * 默认影视海报占位组件（对齐高品质流媒体视觉风格）
 * 用于首页卡片、搜索列表、海报详情页 Hero 轮播在无封面或封面加载失败时的优雅展示。
 *
 * @param title 影视剧片名（用于哈希取色与卡片内标识）
 * @param isHero 是否为详情页大尺寸 Hero 背景形态
 * @param modifier 修饰符
 */
@Composable
fun PosterPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
) {
    val cleanTitle = title.trim()
    val palette = remember(cleanTitle) {
        val hash = if (cleanTitle.isEmpty()) 0 else abs(cleanTitle.hashCode())
        PosterCinemaPalettes[hash % PosterCinemaPalettes.size]
    }

    if (isHero) {
        HeroPosterPlaceholder(
            title = cleanTitle,
            palette = palette,
            modifier = modifier,
        )
    } else {
        CardPosterPlaceholder(
            title = cleanTitle,
            palette = palette,
            modifier = modifier,
        )
    }
}

/**
 * 卡片形态海报占位（首页网格、列表海报）
 */
@Composable
private fun CardPosterPlaceholder(
    title: String,
    palette: List<Color>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette[0],
                        palette[1],
                        Color(0xFF101216),
                    ),
                ),
            )
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.08f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 胶片电影图标胶囊徽标
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.07f), CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(20.dp),
                )
            }

            if (title.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * 详情页 Hero 大尺寸海报占位（视窗顶部 Hero 轮播区）
 * 元素略向上偏移，避开底部 TMDB Logo / 标题 / 评分等内容
 */
@Composable
private fun HeroPosterPlaceholder(
    title: String,
    palette: List<Color>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette[0],
                        palette[1],
                        Color(0xFF0C0D11),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Theaters,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.60f),
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "暂无视频海报",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f),
                    letterSpacing = 1.sp,
                ),
            )
        }
    }
}

