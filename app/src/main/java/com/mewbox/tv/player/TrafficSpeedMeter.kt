package com.mewbox.tv.player

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * 实时下行网速测算器（对齐 FongMi/TV 的 Traffic 实现）
 * 基于 TrafficStats 统计当前进程的网络下行流量速率
 */
class TrafficSpeedMeter(
    private val rxBytesSupplier: () -> Long = {
        TrafficStats.getUidRxBytes(Process.myUid())
    },
    private val clockSupplier: () -> Long = {
        SystemClock.elapsedRealtime()
    },
) {
    private val decimalFormat = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ROOT))
    private var lastTotalRxBytes: Long = -1L
    private var lastTimeStamp: Long = 0L

    /**
     * 重置测速基准值
     */
    fun reset() {
        lastTotalRxBytes = -1L
        lastTimeStamp = 0L
    }

    /**
     * 获取当前测算的网速字符串（如 "0KB/s", "120KB/s", "8.07MB/s"）
     * 若系统不支持 TrafficStats 统计（UNSUPPORTED），返回 null
     */
    fun getSpeed(): String? {
        val totalBytes = rxBytesSupplier()
        if (totalBytes == TrafficStats.UNSUPPORTED.toLong() || totalBytes < 0L) {
            return null
        }

        val nowTimeStamp = clockSupplier()

        if (lastTimeStamp <= 0L || lastTotalRxBytes < 0L) {
            lastTimeStamp = nowTimeStamp
            lastTotalRxBytes = totalBytes
            return "0KB/s"
        }

        val timeDeltaMs = (nowTimeStamp - lastTimeStamp).coerceAtLeast(1L)
        val rxDeltaBytes = (totalBytes - lastTotalRxBytes).coerceAtLeast(0L)
        val bytesPerSec = (rxDeltaBytes * 1000L) / timeDeltaMs
        val speedKb = bytesPerSec / 1024L

        lastTimeStamp = nowTimeStamp
        lastTotalRxBytes = totalBytes

        return if (speedKb < 1000L) {
            "${speedKb}KB/s"
        } else {
            val speedMb = speedKb / 1024f
            "${decimalFormat.format(speedMb)}MB/s"
        }
    }
}

