package com.mewbox.tv.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mewbox.tv.ui.components.EpisodePanelColor
import com.mewbox.tv.ui.components.MewOverlayBackHandler
import com.mewbox.tv.ui.components.rememberOverlayFocusRequester

/**
 * 播放页右侧全高抽屉外壳：选集抽屉与轨道选择抽屉共用。
 *
 * 统一负责：贴右定位、宽度档位、不透明深灰底、无圆角、全高、
 * 点击面板外空白关闭、返回键契约、遥控器初始焦点、上下安全区内边距。
 * 左右不避让 safeInsets（贴屏幕边缘），避免右侧出现多余黑边。
 * 内容层由各抽屉自行提供（选集网格 / 轨道列表）。
 */
private val DrawerWidthFraction = 0.34f
private val DrawerMinWidth = 300.dp
private val DrawerMaxWidth = 460.dp

@Composable
internal fun PlayerDrawerShell(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val insets = rememberPlayerSafeInsets()
    val panelWidth = minOf(
        DrawerMaxWidth,
        maxOf(DrawerMinWidth, (screenWidthDp * DrawerWidthFraction).dp),
    )

    // 与 Dialog / Sheet / 其他自绘层共用返回键与初始焦点契约
    MewOverlayBackHandler(onDismiss = onDismiss)
    val focusRequester = rememberOverlayFocusRequester()

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // 面板外区域点击关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // 右侧全高面板：参考芒果TV选集抽屉，四周圆角 + 半透明透出视频画面
        Surface(
            color = EpisodePanelColor,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = insets.top + 12.dp,
                        bottom = insets.bottom + 12.dp,
                    ),
            ) {
                content()
            }
        }
    }
}
