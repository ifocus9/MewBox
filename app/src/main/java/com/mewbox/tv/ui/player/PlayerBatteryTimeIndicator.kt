package com.mewbox.tv.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class BatterySnapshot(
    val percent: Int,
    val isCharging: Boolean,
)

private fun readBatterySnapshot(intent: Intent?): BatterySnapshot {
    if (intent == null) return BatterySnapshot(percent = -1, isCharging = false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatterySnapshot(percent = percent, isCharging = isCharging)
}

/**
 * 播放器右上角电池与当前时间指示器
 * 对齐移动端播放器顶部栏规范（参考 MewPlayer BatteryTimeView）：
 * 上方为横向圆角电池电量条（20dp x 10dp），带电量内芯与正极凸起，充电为绿色，低电量为红色；
 * 下方为当前系统时间（8sp，紧凑居中对齐）。
 * 外层占 40dp 方格居中，与左侧控制图标严格垂直居中对齐。
 */
@Composable
internal fun PlayerBatteryTimeIndicator(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf(timeFormatter.format(Date())) }

    val initialBattery = remember(context) {
        try {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            readBatterySnapshot(sticky)
        } catch (_: Throwable) {
            BatterySnapshot(percent = -1, isCharging = false)
        }
    }
    var batteryPercent by remember { mutableIntStateOf(initialBattery.percent) }
    var isCharging by remember { mutableStateOf(initialBattery.isCharging) }

    LaunchedEffect(timeFormatter) {
        while (isActive) {
            currentTime = timeFormatter.format(Date())
            delay(1000L)
        }
    }

    DisposableEffect(context, timeFormatter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val snapshot = readBatterySnapshot(intent)
                        batteryPercent = snapshot.percent
                        isCharging = snapshot.isCharging
                    }
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        currentTime = timeFormatter.format(Date())
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Throwable) {}

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 横向小电池 (对标 FongMi 控制栏指示器: 22dp x 11dp)
            Canvas(modifier = Modifier.size(width = 22.dp, height = 11.dp)) {
                val densityValue = density
                val w = size.width
                val h = size.height
                val sw = 1.3f * densityValue
                val tipW = 1.5f * densityValue
                val radius = 2.5f * densityValue

                // 外框
                val bodyRight = w - tipW - densityValue * 0.5f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(
                        (bodyRight - sw).coerceAtLeast(0f),
                        (h - sw).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = sw),
                )

                // 正极凸起
                val tipH = h * 0.45f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(bodyRight, (h - tipH) / 2f),
                    size = Size(tipW, tipH),
                    cornerRadius = CornerRadius(densityValue, densityValue),
                )

                // 电量内芯
                val effectivePercent = if (batteryPercent >= 0) batteryPercent else 100
                val inset = sw + 1f * densityValue
                val left = inset
                val right = bodyRight - inset
                val fillWidth = (right - left) * effectivePercent.coerceIn(0, 100) / 100f
                val fillColor = when {
                    isCharging -> Color(0xFF4CD964)
                    effectivePercent <= 20 -> Color(0xFFFF5252)
                    else -> Color.White
                }
                if (fillWidth > 0f && h > inset * 2) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(left, inset),
                        size = Size(fillWidth, h - inset * 2),
                        cornerRadius = CornerRadius(densityValue, densityValue),
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 当前时间 (对标 FongMi 控制栏时间: 10sp 居中清晰单行)
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

