package com.mewbox.tv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.components.glassSurface
import kotlinx.coroutines.delay

/** 参与首屏错落淡入的卡片数量（前两行），翻页加载的条目不再重复播放入场动画 */
private const val REVEAL_FIRST_WAVE = 12

/**
 * 切换分类/站点后的卡片错落淡入：同一"轮次"（站点|分类）只播一次，
 * 播放完成后记录进 [revealedSet]，滚动复用重建组合时直接显示，避免回滚时反复闪烁。
 */
@Composable
fun StaggeredReveal(
    epoch: Pair<String?, String>,
    index: Int,
    revealedSet: SnapshotStateSet<Pair<String?, String>>,
    content: @Composable () -> Unit,
) {
    if (index >= REVEAL_FIRST_WAVE || epoch in revealedSet) {
        content()
        return
    }
    val alpha = remember(epoch) { Animatable(0f) }
    LaunchedEffect(epoch) {
        delay((index % 6) * 30L)
        alpha.animateTo(1f, tween(240))
        revealedSet.add(epoch)
    }
    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha.value }) {
        content()
    }
}

/** 网格已加载完全部分页时的收尾提示 */
@Composable
fun EndHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(1.dp)
                .background(Color(0xFFDCE0E8)),
        )
        Text(
            text = "已经到底啦",
            fontSize = 12.sp,
            color = Color(0xFFB3B9C4),
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(1.dp)
                .background(Color(0xFFDCE0E8)),
        )
    }
}

/**
 * 右下角“回到顶部”悬浮按钮（支持玻璃效果或 Surface 质感降级）
 */
@Composable
fun HomeBackToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .glassSurface(
                    shape = CircleShape,
                    fallbackColor = Color(0xE6FFFFFF),
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "回到顶部",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

