package com.mewbox.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.ui.theme.BluePrimary

/**
 * 全项目弹层（Dialog / BottomSheet / 自绘覆盖层）唯一的样式与行为契约来源。
 *
 * 设计原则：统一「外壳」和「行为」，不统一「形态」和「内容」。
 * - 形态差异由 TV 场景决定：亮色页面用白底居中卡片、播放页用深色卡片、
 *   首页筛选与详情页选集用底部 Sheet、全屏播放用贴右无圆角抽屉，这些保持各自形态；
 * - 内容层继续保持复用（例如 EpisodePickerContent 被 Sheet 与右侧抽屉共用）；
 * - 改圆角 / 背景 / 宽度 / 阴影 / 内边距只需改本文件的 [OverlayTokens]。
 */
object OverlayTokens {

    /** 居中卡片圆角：原 16 / 18 / 20 / 24dp 四种，统一为一档 */
    val Corner = 18.dp

    /** 底部 Sheet 顶部圆角：原 M3 默认 28dp 与显式 16dp 混用，统一为一档 */
    val SheetCorner = 16.dp

    val Elevation = 8.dp
    val TonalElevation = 6.dp
    val Padding = 20.dp

    val WidthFraction = 0.92f
    val CompactMaxWidth = 440.dp
    val ListMaxWidth = 480.dp
    val ListMaxHeight = 540.dp

    val PlayerWidthFraction = 0.55f
    val PlayerMaxHeightFraction = 0.72f
    val PlayerOuterPadding = 20.dp

    val SheetMaxHeightFraction = 0.8f

    val LightBg = Color.White
    val DarkBg = Color(0xFF211F26)

    val LightContent = Color(0xFF1A1A1A)
    val DarkContent = Color.White

    val DialogTitle = Color(0xFF202124)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF8E8E93)
    val ItemBg = Color(0xFFF2F4F7)
    val OutlinedText = Color(0xFF4E5564)
    val Accent = BluePrimary

    const val EnterDurationMs = 180
}

/** 居中对话框的尺寸档位：表单 / 列表 / 播放器深色卡片 */
enum class MewDialogSpec { Compact, List, Player }

/** 配色变体：亮色卡片 / 播放器深色卡片 */
enum class MewOverlayVariant { Light, Dark }

/**
 * 统一的居中对话框外壳。
 *
 * 内部统一负责：DialogProperties、Surface 背景与圆角与阴影、尺寸档位、
 * 返回键关闭、遥控器初始焦点落点、淡入缩放进场动画。
 * [content] 由各弹层提供自己的 Column（内边距统一引用 [OverlayTokens.Padding]）。
 */
@Composable
fun MewDialogShell(
    onDismiss: () -> Unit,
    spec: MewDialogSpec = MewDialogSpec.Compact,
    variant: MewOverlayVariant = MewOverlayVariant.Light,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var shown by remember { mutableStateOf(false) }
    val isLand = LocalConfiguration.current.screenWidthDp > 600

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 行为契约：返回键一律关闭弹层，不再依赖各页面外层的 BackHandler
        BackHandler(onBack = onDismiss)

        LaunchedEffect(Unit) { shown = true }

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(OverlayTokens.EnterDurationMs)) +
                scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(OverlayTokens.EnterDurationMs),
                ),
        ) {
            // 焦点请求必须放在 AnimatedVisibility 内部：进场动画初始 visible=false 时
            // Surface 尚未进入组合，focusRequester 未绑定节点，过早请求会静默失败，
            // 导致遥控器焦点仍留在弹层背后的页面（要按一次方向键才进来）。
            // 等一帧确保节点附着完成。
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
            }
            Surface(
                modifier = Modifier
                    .then(
                        when (spec) {
                            MewDialogSpec.Compact -> Modifier
                                .fillMaxWidth(OverlayTokens.WidthFraction)
                                .widthIn(max = OverlayTokens.CompactMaxWidth)
                            MewDialogSpec.List -> Modifier
                                .fillMaxWidth(OverlayTokens.WidthFraction)
                                .widthIn(max = OverlayTokens.ListMaxWidth)
                                .heightIn(max = OverlayTokens.ListMaxHeight)
                            MewDialogSpec.Player -> Modifier
                                .fillMaxWidth(
                                    if (isLand) OverlayTokens.PlayerWidthFraction
                                    else OverlayTokens.WidthFraction,
                                )
                                .fillMaxHeight(OverlayTokens.PlayerMaxHeightFraction)
                                .padding(vertical = OverlayTokens.PlayerOuterPadding)
                        },
                    )
                    .focusRequester(focusRequester)
                    .focusable(),
                shape = RoundedCornerShape(OverlayTokens.Corner),
                color = when (variant) {
                    MewOverlayVariant.Light -> OverlayTokens.LightBg
                    MewOverlayVariant.Dark -> OverlayTokens.DarkBg
                },
                contentColor = when (variant) {
                    MewOverlayVariant.Light -> OverlayTokens.LightContent
                    else -> OverlayTokens.DarkContent
                },
                shadowElevation = if (variant == MewOverlayVariant.Light) OverlayTokens.Elevation else 0.dp,
                tonalElevation = if (variant == MewOverlayVariant.Light) 0.dp else OverlayTokens.TonalElevation,
            ) {
                content()
            }
        }
    }
}

/**
 * 统一的底部 Sheet 外壳。
 *
 * 内部统一负责：skipPartiallyExpanded、容器色（亮色跟随主题、深色用令牌）、
 * 顶部圆角、dragHandle、返回键、遥控器初始焦点。
 * 进出场动画沿用 Material3 自带的滑动，不额外叠加，避免两层动画打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MewSheetShell(
    onDismiss: () -> Unit,
    variant: MewOverlayVariant = MewOverlayVariant.Light,
    content: @Composable (focusModifier: Modifier) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = when (variant) {
            MewOverlayVariant.Light -> MaterialTheme.colorScheme.surface
            MewOverlayVariant.Dark -> OverlayTokens.DarkBg
        },
        contentColor = when (variant) {
            MewOverlayVariant.Light -> MaterialTheme.colorScheme.onSurface
            else -> OverlayTokens.DarkContent
        },
        shape = RoundedCornerShape(
            topStart = OverlayTokens.SheetCorner,
            topEnd = OverlayTokens.SheetCorner,
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = if (variant == MewOverlayVariant.Light) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                } else {
                    Color.White.copy(alpha = 0.35f)
                },
            )
        },
    ) {
        BackHandler(onBack = onDismiss)
        // 与 Dialog 外壳同理：等一帧让节点附着后再抢焦点
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
        // 焦点必须落在 Sheet 内容容器上：挂在 ModalBottomSheet 根上会落到上半屏
        // 的 scrim 遮罩节点，遥控器按方向键无法进入下方的 Sheet 内容。
        content(
            Modifier
                .focusRequester(focusRequester)
                .focusable(),
        )
    }
}

/**
 * 自绘覆盖层（右侧抽屉、侧边悬浮条等）共用的返回键契约。
 * 它们不经过 Dialog / Sheet，需要自己接住返回键，行为与上面两个外壳保持一致。
 */
@Composable
fun MewOverlayBackHandler(
    enabled: Boolean = true,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onDismiss)
}

/** 自绘覆盖层共用的初始焦点契约：把遥控器焦点收进覆盖层内部。 */
@Composable
fun rememberOverlayFocusRequester(): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    // 等一帧再请求，避免调用方节点尚未组合时请求被吞掉
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    return focusRequester
}
