package com.mewbox.tv.player.exo

import android.app.ActivityManager
import android.content.Context

/**
 * 移植自 MewPlayer / FongMi：
 * 根据设备运行内存情况（低内存设备 / 普通设备 / 最大堆内存限制）动态自适应计算缓冲预算，
 * 在保证高码率 4K 播放拥有充足缓冲的同时，彻底杜绝低内存设备 (1G/2G RAM 电视盒子) 产生 OutOfMemoryError。
 */
object ExoBufferBudget {

    const val MIN_TARGET_BYTES = 16 * 1024 * 1024 // 16MB
    const val MAX_TARGET_BYTES = 128 * 1024 * 1024 // 128MB
    private const val LOW_RAM_PERCENT = 20
    private const val NORMAL_RAM_PERCENT = 30
    private const val LOW_RAM_RESERVED_BYTES = 48 * 1024 * 1024
    private const val NORMAL_RESERVED_BYTES = 64 * 1024 * 1024

    fun getEffectiveTargetBytes(context: Context, requestedTargetBytes: Int = MAX_TARGET_BYTES): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRamDevice = manager?.isLowRamDevice ?: false
        val heapLimit = Runtime.getRuntime().maxMemory().coerceAtLeast(0L)

        val percent = if (lowRamDevice) LOW_RAM_PERCENT else NORMAL_RAM_PERCENT
        val reservedHeadroom = if (lowRamDevice) LOW_RAM_RESERVED_BYTES else NORMAL_RESERVED_BYTES
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
