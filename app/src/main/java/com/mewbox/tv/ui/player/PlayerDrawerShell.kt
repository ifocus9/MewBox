package com.mewbox.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.components.MewOverlayBackHandler
import com.mewbox.tv.ui.components.rememberOverlayFocusRequester
import com.mewbox.tv.ui.theme.MewBoxTheme

private const val PLAYER_SHEET_EXIT_MS = PLAYER_CONTROLS_EXIT_MS + 30

private val DrawerWidthFraction = 0.34f
private val DrawerMinWidth = 300.dp
private val DrawerMaxWidth = 460.dp
private val SheetHeightFraction = 0.56f
private val SheetMinHeight = 280.dp
private val SheetMaxHeight = 480.dp
private val DrawerPanelColor = Color(0xD9101118)
private val DrawerLandscapeShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
private val DrawerPortraitShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

private data class PlayerSheetMotion(
    val scope: AnimatedVisibilityScope,
    val isLandscape: Boolean,
)

private val LocalPlayerSheetMotion = staticCompositionLocalOf<PlayerSheetMotion?> { null }

/**
 * 自适应播放器抽屉转场宿主（不重复包裹 [PlayerDrawerShell]，由子抽屉自带外壳）：
 * - 背景遮罩原地淡入淡出，不跟随平移
 * - 抽屉卡片自身通过 [LocalPlayerSheetMotion] 执行横屏侧滑 / 竖屏上滑
 */
@Composable
fun PlayerAdaptiveSheetHost(
    visible: Boolean,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp },
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(PLAYER_SHEET_ANIM_MS)),
        exit = fadeOut(tween(PLAYER_SHEET_EXIT_MS)),
        modifier = modifier.fillMaxSize(),
    ) {
        val motion = remember(this, isLandscape) {
            PlayerSheetMotion(scope = this, isLandscape = isLandscape)
        }
        CompositionLocalProvider(LocalPlayerSheetMotion provides motion) {
            content()
        }
    }
}

/**
 * 播放页自适应抽屉外壳：选集、轨道、画质、倍速与更多设置抽屉共用。
 *
 * - 横屏：右侧贴边全高面板（上下右贴边、左侧 20.dp 圆角、无边框、半透深底）
 * - 竖屏：底部弹出圆角 Sheet（顶部 24.dp 圆角 + 拖拽小横条，确定高度保障内部列表 weight(1f) 有界）
 */
@Composable
internal fun PlayerDrawerShell(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp },
    content: @Composable ColumnScope.() -> Unit,
) {
    val config = LocalConfiguration.current
    val insets = rememberPlayerSafeInsets()
    val sheetMotion = LocalPlayerSheetMotion.current
    val effectiveLandscape = sheetMotion?.isLandscape ?: isLandscape

    MewOverlayBackHandler(onDismiss = onDismiss)
    val focusRequester = rememberOverlayFocusRequester()

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // 面板外半透遮罩：固定铺满屏幕，仅随外层 AnimatedVisibility 原地淡入淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.24f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        val panelShape = if (effectiveLandscape) DrawerLandscapeShape else DrawerPortraitShape
        val slideModifier: Modifier.() -> Modifier = {
            if (sheetMotion != null) {
                with(sheetMotion.scope) {
                    val enterSlide = if (effectiveLandscape) {
                        slideInHorizontally(tween(PLAYER_SHEET_ANIM_MS)) { it }
                    } else {
                        slideInVertically(tween(PLAYER_SHEET_ANIM_MS)) { it }
                    }
                    val exitSlide = if (effectiveLandscape) {
                        slideOutHorizontally(tween(PLAYER_SHEET_EXIT_MS)) { it }
                    } else {
                        slideOutVertically(tween(PLAYER_SHEET_EXIT_MS)) { it }
                    }
                    animateEnterExit(enter = enterSlide, exit = exitSlide)
                }
            } else {
                this
            }
        }

        val panelModifier = if (effectiveLandscape) {
            val panelWidth = (config.screenWidthDp * DrawerWidthFraction)
                .dp
                .coerceIn(DrawerMinWidth, DrawerMaxWidth)
            Modifier
                .align(Alignment.CenterEnd)
                .slideModifier()
                .width(panelWidth)
                .fillMaxHeight()
        } else {
            val panelHeight = (config.screenHeightDp * SheetHeightFraction)
                .dp
                .coerceIn(SheetMinHeight, SheetMaxHeight)
            Modifier
                .align(Alignment.BottomCenter)
                .slideModifier()
                .fillMaxWidth()
                .height(panelHeight)
        }

        Surface(
            color = DrawerPanelColor,
            shape = panelShape,
            modifier = panelModifier
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
                        top = if (effectiveLandscape) insets.top + 12.dp else 10.dp,
                        bottom = if (effectiveLandscape) insets.bottom + 12.dp else insets.bottom + 12.dp,
                    ),
            ) {
                if (!effectiveLandscape) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                content()
            }
        }
    }
}

/**
 * 抽屉顶部胶囊 Tab 切换栏（用于三合一轨道抽屉与更多设置抽屉）
 */
@Composable
internal fun PlayerDrawerTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MewBoxTheme.colors.panelAccent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) accent.copy(alpha = 0.28f) else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = if (selected) accent else Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
