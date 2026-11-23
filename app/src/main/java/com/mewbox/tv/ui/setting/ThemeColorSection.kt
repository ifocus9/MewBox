package com.mewbox.tv.ui.setting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.ThemeColor
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.ui.theme.contrastOnAccent

/**
 * 外观设置中的主题色摘要行：展示当前选中色块与名称/十六进制，点击打开底部主题色抽屉。
 */
@Composable
fun ThemeColorRow(
    selectedArgb: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedSelected = ThemeColor.normalizeArgb(selectedArgb)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "主题色",
            fontSize = 16.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(normalizedSelected))
                .border(0.75.dp, MewBoxTheme.colors.subtleBorder, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = ThemeColor.displayLabel(normalizedSelected),
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MewBoxTheme.colors.chevron,
        )
    }
}

@Composable
internal fun PresetColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ringColor by animateColorAsState(
        targetValue = if (selected) color else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "presetRing_$name",
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = name, onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, ringColor, CircleShape) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 28.dp else 30.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "已选中 $name",
                    tint = contrastOnAccent(color),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
