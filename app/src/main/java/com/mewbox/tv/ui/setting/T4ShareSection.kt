package com.mewbox.tv.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 设置页「T4 后台服务」区块：保活开关、本机/局域网 T4 订阅地址一键复制及电池优化检测跳转。
 */
@Composable
fun T4ShareSection(
    enabled: Boolean,
    switching: Boolean,
    localUrl: String,
    lanUrl: String,
    batteryOptimizationIgnored: Boolean,
    onToggle: (Boolean) -> Unit,
    onCopyUrl: (label: String, url: String) -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "T4 后台服务",
                fontSize = 16.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Normal,
            )
            Spacer(Modifier.weight(1f))
            if (switching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = if (MewBoxTheme.isDark) Color(0xFFD5D9E2) else Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = enabled && (localUrl.isNotBlank() || !batteryOptimizationIgnored),
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (localUrl.isNotBlank()) {
                    SettingDivider()
                    T4AddressRow(
                        label = "本机",
                        url = localUrl,
                        onClick = { onCopyUrl("本机", localUrl) },
                    )
                }
                if (lanUrl.isNotBlank()) {
                    SettingDivider()
                    T4AddressRow(
                        label = "局域网",
                        url = lanUrl,
                        onClick = { onCopyUrl("局域网", lanUrl) },
                    )
                }
                if (!batteryOptimizationIgnored) {
                    SettingDivider()
                    SettingRow(
                        title = "后台电池",
                        value = "去设为无限制",
                        onClick = onOpenBatterySettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun T4AddressRow(
    label: String,
    url: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = url,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "复制${label}地址",
            modifier = Modifier.size(18.dp),
            tint = TextSecondary,
        )
    }
}
