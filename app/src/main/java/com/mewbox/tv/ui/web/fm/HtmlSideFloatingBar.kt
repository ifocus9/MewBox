package com.mewbox.tv.ui.web.fm

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val HandleBlue = Color(0xFF0A7AFF)
private val CompassBadgeBg = Color(0xFFE3F0FF)
private val CapsuleTextPrimary = Color(0xFF1A1A1A)
private val CapsuleBorder = Color(0x14000000)

/**
 * HTML 源侧边悬浮控制条（视觉与交互对齐截图与 MewPlayer WebHomeChromeController）：
 * - 默认收起态：紧贴屏幕左侧边缘的蓝色圆角竖条（支持上下拖拽定位，点击展开）
 * - 展开态：白底圆角胶囊（浅蓝底指南针图标 + 当前订阅源名称）+ 右侧白底圆形刷新按钮
 */
@Composable
fun HtmlSideFloatingBar(
    subscriptionName: String,
    visible: Boolean,
    showControls: Boolean,
    onSubscriptionClick: () -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var expanded by rememberSaveable { mutableStateOf(false) }

    // 展开后无操作 5 秒自动收起，保持沉浸浏览
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(5_000L)
            expanded = false
        }
    }

    // 展开状态下拦截返回键优先收起悬浮条
    BackHandler(enabled = expanded) {
        expanded = false
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 展开态下全屏透明点击层：点击空白处自动收起悬浮条
        if (expanded && showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        expanded = false
                    },
            )
        }

        val density = LocalDensity.current
        val minTopPx = with(density) { 56.dp.toPx() }
        val maxBottomPx = with(density) { (maxHeight - 120.dp). coerceAtLeast(80.dp).toPx() }
        val defaultY = with(density) { 112.dp.toPx() }.coerceIn(minTopPx, maxBottomPx)

        var offsetYPx by rememberSaveable { mutableFloatStateOf(defaultY) }
        val clampedY = offsetYPx.coerceIn(minTopPx, maxBottomPx)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset { IntOffset(0, clampedY.roundToInt()) }
                .height(50.dp)
                .pointerInput(minTopPx, maxBottomPx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        offsetYPx = (offsetYPx + dragAmount).coerceIn(minTopPx, maxBottomPx)
                    }
                },
        ) {
            // 左侧贴边蓝色把手（固定宽度 16dp，避免展开/收起时宽度突变挤压胶囊；高度 50dp 与胶囊容器对齐）
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .zIndex(1f)
                    .width(16.dp)
                    .height(50.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (showControls) {
                            expanded = !expanded
                        } else {
                            onSubscriptionClick()
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp))
                        .background(HandleBlue),
                )
            }

            // 展开态：订阅切换胶囊 + 圆形刷新按钮
            AnimatedVisibility(
                visible = expanded && showControls,
                enter = fadeIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    initialOffsetX = { -it },
                ),
                exit = fadeOut(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + slideOutHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    targetOffsetX = { -it },
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(50.dp)
                        .padding(start = 4.dp, end = 12.dp),
                ) {
                    // 白色圆角订阅胶囊：[浅蓝底指南针图标 + 当前订阅名称]
                    Surface(
                        onClick = {
                            expanded = false
                            onSubscriptionClick()
                        },
                        shape = RoundedCornerShape(50),
                        color = Color.White,
                        shadowElevation = 4.dp,
                        border = BorderStroke(0.5.dp, CapsuleBorder),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 6.dp, end = 14.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(CompassBadgeBg),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Explore,
                                    contentDescription = "当前订阅",
                                    tint = HandleBlue,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = subscriptionName.ifBlank { "网页源" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = CapsuleTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 165.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(7.dp))

                    // 右侧白色圆形刷新按钮
                    Surface(
                        onClick = {
                            expanded = false
                            onRefreshClick()
                        },
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 4.dp,
                        border = BorderStroke(0.5.dp, CapsuleBorder),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新网页",
                                tint = CapsuleTextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

