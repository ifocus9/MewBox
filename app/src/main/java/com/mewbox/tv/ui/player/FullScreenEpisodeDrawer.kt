package com.mewbox.tv.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.ui.components.EpisodePanelColor
import com.mewbox.tv.ui.components.EpisodePickerContent
import com.mewbox.tv.ui.components.rememberEpisodeDisplayNames
import kotlin.math.roundToInt

/**
 * 全屏播放时的右侧选集面板。
 *
 * 版式完全对齐 MewPlayer 播放页的 EpisodeListDialog：
 * - 贴右边、无圆角、近黑半透明底（#E6101118），内容四周 12dp 内边距；
 * - 宽度 = min(560dp, max(360dp, 屏宽 × 44%))；
 * - 头部「选集 + 全N集 + 精简 / 倒序」，下面是 1-20 / 21-40 分段标签；
 * - 剧集网格按标题长度自适应 2~5 列、40dp 高、当前集蓝底白边；
 * - 打开时当前集滚到列表上方，点空白处或返回键关闭。
 */
@Composable
fun FullScreenEpisodeDrawer(
    episodes: List<Episode>,
    currentEpisode: Episode?,
    isReversed: Boolean,
    isCompactEpisodeTitle: Boolean,
    onToggleSort: () -> Unit,
    onToggleCompact: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val insets = rememberPlayerSafeInsets()
    val panelWidth = minOf(560.dp, maxOf(360.dp, (screenWidthDp * 0.44f).dp) + insets.horizontal)
    val displayNames = rememberEpisodeDisplayNames(episodes, isCompactEpisodeTitle)
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxSize()) {
        // 面板外区域点击关闭（MewPlayer 靠 overlay 空白点击退出，不额外放关闭按钮）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClose,
                ),
        )

        Surface(
            color = EpisodePanelColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                // 面板本身吞掉点击，避免点面板空白处穿透到遮罩把抽屉关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            EpisodePickerContent(
                episodes = episodes,
                displayNames = displayNames,
                currentEpisode = currentEpisode,
                isReversed = isReversed,
                isCompact = isCompactEpisodeTitle,
                maxSpan = 5,
                onToggleCompact = onToggleCompact,
                onToggleReverse = onToggleSort,
                onSelectEpisode = onSelectEpisode,
                horizontalPadding = 0.dp,
                bottomPadding = 12.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        end = insets.horizontal + 12.dp,
                        top = insets.top + 12.dp,
                        bottom = insets.bottom + 12.dp,
                    ),
            )
        }
    }
}
