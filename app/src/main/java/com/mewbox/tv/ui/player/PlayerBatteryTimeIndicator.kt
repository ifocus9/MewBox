package com.mewbox.tv.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

private val BatteryBodyShape = RoundedCornerShape(3.5.dp)
private val BatteryTipShape = RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp)
private val BatteryChargingColor = Color(0xFF34C759)
private val BatteryLowColor = Color(0xFFFF453A)
private val BatteryNormalFillColor = Color.White.copy(alpha = 0.92f)
private val BatteryInvertTextColor = Color(0xFF0E1015)

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
 * 播放器右上角单行横排系统状态指示器：系统时间 + 内嵌电量百分比的横向圆角电池图标
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
        } catch (t: Throwable) {
            Timber.w(t, "读取初始电池电量失败")
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
        } catch (t: Throwable) {
            Timber.w(t, "注册播放器电量与时间广播失败")
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Timber.w(t, "注销播放器电量与时间广播失败")
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = currentTime,
            color = Color.White.copy(alpha = 0.92f),
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
        )

        Spacer(modifier = Modifier.width(6.dp))

        BatteryPillIcon(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
        )
    }
}

@Composable
private fun BatteryPillIcon(
    batteryPercent: Int,
    isCharging: Boolean,
) {
    val effectivePercent = if (batteryPercent >= 0) batteryPercent.coerceIn(0, 100) else 100
    val fillFraction = effectivePercent / 100f
    val isColoredFill = isCharging || effectivePercent <= 20
    val fillColor = when {
        isCharging -> BatteryChargingColor
        effectivePercent <= 20 -> BatteryLowColor
        else -> BatteryNormalFillColor
    }
    val density = LocalDensity.current
    val digitFontSize = with(density) { 8.5.dp.toSp() }
    val digitStyle = TextStyle(
        fontSize = digitFontSize,
        lineHeight = digitFontSize,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 21.dp, height = 11.5.dp)
                .clip(BatteryBodyShape)
                .background(Color.White.copy(alpha = 0.32f)),
        ) {
            if (fillFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .background(fillColor),
                )
            }

            if (batteryPercent >= 0) {
                val label = effectivePercent.toString()
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = digitStyle,
                        maxLines = 1,
                    )
                }

                // 常规白填充态：对已填充区域裁切叠加深色反色数字，确保任意电量下均有高对比度
                if (!isColoredFill && fillFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipRect(right = size.width * fillFraction) {
                                    this@drawWithContent.drawContent()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = BatteryInvertTextColor,
                            style = digitStyle,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(0.5.dp))

        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(4.5.dp)
                .clip(BatteryTipShape)
                .background(Color.White.copy(alpha = 0.50f)),
        )
    }
}
