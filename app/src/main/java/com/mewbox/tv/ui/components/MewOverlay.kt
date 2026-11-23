package com.mewbox.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mewbox.tv.ui.theme.MewBoxTheme
import kotlinx.coroutines.launch

/**
 * 全项目弹层（BottomSheet / 自绘覆盖层）唯一的样式与行为契约来源。
 *
 * 设计原则：统一「外壳」和「行为」，不统一「形态」和「内容」。
 * - 改圆角 / 背景 / 遮罩 / 内边距只需改本文件的 [OverlayTokens]。
 */
object OverlayTokens {

    /** 底部 Sheet 顶部圆角 */
    val SheetCorner = 16.dp

    val Padding = 20.dp
    val SheetMaxHeightFraction = 0.8f

    /** 底部抽屉半透明遮罩色（跟随深浅色主题） */
    val ScrimColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MewBoxTheme.colors.scrim

    val DarkBg = Color(0xFF211F26)
    val DarkContent = Color.White

    val DialogTitle: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    val TextPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    val TextSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant

    val SheetItemBg: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceVariant

    val OutlinedText: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant

    val Accent: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary
}

/** 配色变体：亮色卡片 / 播放器深色卡片 */
enum class MewOverlayVariant { Light, Dark }

/**
 * 提供给弹层内部按钮（如取消）的平滑退场关闭回调；
 * 底部抽屉会先下滑收起再移除窗口。
 */
val LocalMewOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 统一的底部 Sheet 外壳。
 *
 * 内部统一负责：skipPartiallyExpanded、统一遮罩色 [OverlayTokens.ScrimColor]、
 * 容器色（亮色跟随主题、深色用令牌）、顶部圆角、dragHandle、返回键平滑收起、遥控器初始焦点。
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
    val scope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }

    val dismissSheet: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            scope.launch {
                sheetState.hide()
            }.invokeOnCompletion {
                if (!sheetState.isVisible) {
                    onDismiss()
                } else {
                    isDismissing = false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = OverlayTokens.ScrimColor,
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
                    MaterialTheme.colorScheme.onSurface.copy(alpha = if (MewBoxTheme.isDark) 0.25f else 0.35f)
                } else {
                    Color.White.copy(alpha = 0.35f)
                },
            )
        },
    ) {
        // 先平滑下滑收起再移除，避免直接硬销毁吞掉退场动画
        BackHandler(onBack = dismissSheet)
        // 等一帧让节点附着后再抢焦点
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
        // 焦点必须落在 Sheet 内容容器上：挂在 ModalBottomSheet 根上会落到上半屏
        // 的 scrim 遮罩节点，遥控器按方向键无法进入下方的 Sheet 内容。
        CompositionLocalProvider(LocalMewOverlayDismiss provides dismissSheet) {
            content(
                Modifier
                    .focusRequester(focusRequester)
                    .focusable(),
            )
        }
    }
}

/**
 * 自绘覆盖层（右侧抽屉、侧边悬浮条等）共用的返回键契约。
 * 它们不经过 Sheet，需要自己接住返回键，行为与上面外壳保持一致。
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
