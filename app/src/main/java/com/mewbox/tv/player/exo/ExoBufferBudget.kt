package com.mewbox.tv.player.exo

import android.app.ActivityManager
import android.content.Context

/**
 * 移植自 webhtv / FongMi：
 * 根据设备运行内存情况（低内存设备 / 普通设备 / 开启 largeHeap 的大内存手机）动态自适应计算缓冲预算，
 * 在保证高码率 4K 原盘 / Remux 播放拥有充足缓冲纵深（大堆设备最高 512MB）的同时，彻底杜绝低内存设备 (1G/2G RAM 电视盒子) 产生 OutOfMemoryError。
 */
object ExoBufferBudget {

    const val MIN_TARGET_BYTES = 16 * 1024 * 1024 // 16MB
    const val MAX_TARGET_BYTES = 512 * 1024 * 1024 // 512MB（针对高码率 4K 原盘/Remux 提升大堆设备缓冲上限）
    private const val LOW_RAM_PERCENT = 20
    private const val NORMAL_RAM_PERCENT = 35
    private const val LARGE_HEAP_RAM_PERCENT = 65
    private const val LARGE_HEAP_THRESHOLD_BYTES = 384L * 1024 * 1024 // 384MB
    private const val LOW_RAM_RESERVED_BYTES = 48 * 1024 * 1024
    private const val NORMAL_RESERVED_BYTES = 64 * 1024 * 1024
    private const val LARGE_HEAP_RESERVED_BYTES = 128 * 1024 * 1024 // 大缓冲下为 UI/图片/解复用留足 Java 堆安全余量

    fun getEffectiveTargetBytes(context: Context, requestedTargetBytes: Int = MAX_TARGET_BYTES): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRamDevice = manager?.isLowRamDevice ?: false
        val heapLimit = Runtime.getRuntime().maxMemory().coerceAtLeast(0L)
        return calculateEffectiveTargetBytes(
            heapLimitBytes = heapLimit,
            lowRamDevice = lowRamDevice,
            requestedTargetBytes = requestedTargetBytes,
        )
    }

    /** 根据堆内存上限与低内存标识纯计算缓冲字节上限，便于单元测试覆盖各档位设备。 */
    internal fun calculateEffectiveTargetBytes(
        heapLimitBytes: Long,
        lowRamDevice: Boolean,
        requestedTargetBytes: Int = MAX_TARGET_BYTES,
    ): Int {
        val heapLimit = heapLimitBytes.coerceAtLeast(0L)
        val isLargeHeap = !lowRamDevice && heapLimit >= LARGE_HEAP_THRESHOLD_BYTES
        val percent = when {
            lowRamDevice -> LOW_RAM_PERCENT
            isLargeHeap -> LARGE_HEAP_RAM_PERCENT
            else -> NORMAL_RAM_PERCENT
        }
        val reservedHeadroom = when {
            lowRamDevice -> LOW_RAM_RESERVED_BYTES
            isLargeHeap -> LARGE_HEAP_RESERVED_BYTES
            else -> NORMAL_RESERVED_BYTES
        }
        val proportionalBudget = heapLimit * percent / 100
        val minimumBudget = MIN_TARGET_BYTES.toLong().coerceAtMost(heapLimit)
        val percentageBudget = minimumBudget.coerceAtLeast(proportionalBudget)
        val availableAfterReserve = (heapLimit - reservedHeadroom).coerceAtLeast(0L)
        val headroomBudget = minimumBudget.coerceAtLeast(availableAfterReserve)
        val heapBudget = heapLimit.coerceAtMost(
            MAX_TARGET_BYTES.toLong().coerceAtMost(percentageBudget.coerceAtMost(headroomBudget))
        )
        val requested = if (requestedTargetBytes > 0) requestedTargetBytes else MAX_TARGET_BYTES
        return requested.coerceAtMost(heapBudget.toInt()).coerceAtLeast(MIN_TARGET_BYTES)
    }
}
