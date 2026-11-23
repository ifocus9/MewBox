package com.mewbox.tv.ui.setting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.ThemeMode
import com.mewbox.tv.ui.theme.MewBoxTheme

internal val BgCard: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

internal val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

internal val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/** 分组标题：卡片外的浅灰小标签，主设置页与 TMDB 二级页共用。 */
@Composable
fun SettingSectionLabel(title: String) {
    Text(
        text = title,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
    )
}

/** 同一分组卡片内的条目分隔线：左右各内缩 16dp，与条目标题对齐。 */
@Composable
fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun SettingRow(
    title: String,
    value: String = "",
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                text = value,
                fontSize = 14.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .widthIn(max = 200.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MewBoxTheme.colors.chevron,
        )
    }
}

/**
 * 主题模式内联三段切换行：【自动】【深色】【浅色】
 */
@Composable
fun ThemeModeRow(
    selectedMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ThemeMode.entries
    val selectedIndex = modes.indexOf(selectedMode).coerceAtLeast(0)
    val capsule = RoundedCornerShape(percent = 50)
    val slide by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "ThemeModeSlide",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "主题模式",
            fontSize = 16.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(
            modifier = Modifier
                .width(186.dp)
                .height(34.dp)
                .clip(capsule)
                .background(MaterialTheme.colorScheme.surfaceVariant, capsule)
                .padding(3.dp),
        ) {
            val segmentWidth = maxWidth / modes.size
            Box(
                modifier = Modifier
                    .offset(x = segmentWidth * slide)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, capsule),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                modes.forEach { mode ->
                    val isSelected = mode == selectedMode
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "ThemeModeLabel_${mode.storageValue}",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(capsule)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = mode.label,
                            ) { onSelect(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 针对过长链接做居中省略展示
 */
fun formatMiddleEllipsis(text: String, maxLength: Int = 28): String {
    if (text.length <= maxLength) return text
    val prefixLen = maxLength / 2
    val suffixLen = maxLength / 2 - 3
    return "${text.take(prefixLen)}...${text.takeLast(suffixLen)}"
}
