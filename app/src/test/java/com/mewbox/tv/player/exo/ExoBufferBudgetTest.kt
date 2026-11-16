package com.mewbox.tv.player.exo

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import com.mewbox.tv.player.Players
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class ExoBufferBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun largeHeapDevices_scaleUpTo512MbCapWithSafetyHeadroom() {
        // 512MB largeHeap（常见旗舰手机）：按 65% ≈ 332.8MB，留足 179.2MB Java 堆余量
        val budget512 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 512L * mb,
            lowRamDevice = false,
        )
        assertEquals((512L * mb * 65 / 100).toInt(), budget512)
        assertTrue(512L * mb - budget512 >= 128L * mb)

        // 768MB largeHeap：按 65% ≈ 499.2MB
        val budget768 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 768L * mb,
            lowRamDevice = false,
        )
        assertEquals((768L * mb * 65 / 100).toInt(), budget768)

        // 1024MB largeHeap：封顶 MAX_TARGET_BYTES (512MB)
        val budget1024 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 1024L * mb,
            lowRamDevice = false,
        )
        assertEquals(ExoBufferBudget.MAX_TARGET_BYTES, budget1024)
    }

    @Test
    fun normalAndLowRamDevices_preserveConservativeCaps() {
        // 384MB 阈值边界：按 65% = 249.6MB，仍保留 > 128MB 安全余量
        val budget384 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 384L * mb,
            lowRamDevice = false,
        )
        assertEquals((384L * mb * 65 / 100).toInt(), budget384)
        assertTrue(384L * mb - budget384 >= 128L * mb)

        // 256MB 普通设备（未达 largeHeap 阈值）：维持 35% = 89.6MB
        val budget256 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 256L * mb,
            lowRamDevice = false,
        )
        assertEquals((256L * mb * 35 / 100).toInt(), budget256)

        // 128MB 低内存电视盒子：维持 20% = 25.6MB
        val lowRam128 = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 128L * mb,
            lowRamDevice = true,
        )
        assertEquals((128L * mb * 20 / 100).toInt(), lowRam128)
    }

    @Test
    fun requestedCapAndMinimumFloor_areRespected() {
        val capped = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 1024L * mb,
            lowRamDevice = false,
            requestedTargetBytes = (128L * mb).toInt(),
        )
        assertEquals((128L * mb).toInt(), capped)

        val fallbackFloor = ExoBufferBudget.calculateEffectiveTargetBytes(
            heapLimitBytes = 32L * mb,
            lowRamDevice = true,
        )
        assertEquals(ExoBufferBudget.MIN_TARGET_BYTES, fallbackFloor)
    }

    @Test
    fun buildLoadControl_disablesBackBufferAndRespectsTimeAndSizeBounds() {
        val targetBytes = (332L * mb).toInt()
        val loadControl = Players.buildLoadControl(targetBytes)
        loadControl.onPrepared(PlayerId.UNSET)

        assertEquals(0L, loadControl.getBackBufferDurationUs(PlayerId.UNSET))
        assertFalse(loadControl.retainBackBufferFromKeyframe(PlayerId.UNSET))

        val mediaPeriodId = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any())
        val paramsUnderMin = LoadControl.Parameters(
            PlayerId.UNSET,
            androidx.media3.common.Timeline.EMPTY,
            mediaPeriodId,
            0L,
            10_000_000L, // 10s < 50s minBufferMs
            1.0f,
            true,
            false,
            androidx.media3.common.C.TIME_UNSET,
            androidx.media3.common.C.TIME_UNSET,
        )
        loadControl.onTracksSelected(
            paramsUnderMin,
            androidx.media3.exoplayer.source.TrackGroupArray.EMPTY,
            emptyArray(),
        )
        assertTrue(loadControl.shouldContinueLoading(paramsUnderMin))

        // 达到 120s maxBufferMs 后停止继续拉流
        val paramsAtMax = LoadControl.Parameters(
            PlayerId.UNSET,
            androidx.media3.common.Timeline.EMPTY,
            mediaPeriodId,
            0L,
            120_000_000L,
            1.0f,
            true,
            false,
            androidx.media3.common.C.TIME_UNSET,
            androidx.media3.common.C.TIME_UNSET,
        )
        assertFalse(loadControl.shouldContinueLoading(paramsAtMax))

        // 若分配字节已达到 targetBytes，即使时长不足 50s 也停止继续拉流（字节上限优先）
        val tinyTargetControl = Players.buildLoadControl(64 * 1024)
        tinyTargetControl.onPrepared(PlayerId.UNSET)
        tinyTargetControl.onTracksSelected(
            paramsUnderMin,
            androidx.media3.exoplayer.source.TrackGroupArray.EMPTY,
            emptyArray(),
        )
        tinyTargetControl.getAllocator().allocate()
        assertFalse(tinyTargetControl.shouldContinueLoading(paramsUnderMin))

        loadControl.onReleased(PlayerId.UNSET)
        tinyTargetControl.onReleased(PlayerId.UNSET)
    }
}
