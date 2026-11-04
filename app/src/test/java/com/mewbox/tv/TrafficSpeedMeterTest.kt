package com.mewbox.tv

import com.mewbox.tv.player.TrafficSpeedMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficSpeedMeterTest {

    @Test
    fun getSpeed_whenUnsupported_returnsNull() {
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { -1L },
            clockSupplier = { 1000L },
        )
        assertNull(meter.getSpeed())
    }

    @Test
    fun getSpeed_firstTick_returnsZeroKb() {
        var bytes = 100_000L
        var time = 1000L
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { bytes },
            clockSupplier = { time },
        )

        assertEquals("0KB/s", meter.getSpeed())
    }

    @Test
    fun getSpeed_under1000Kb_formatsAsKbPerSec() {
        var bytes = 100_000L
        var time = 1000L
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { bytes },
            clockSupplier = { time },
        )

        meter.getSpeed() // baseline: 100,000 bytes at 1000ms

        // 1 second later, 512,000 bytes added (500 KB)
        bytes += 500L * 1024L
        time += 1000L

        assertEquals("500KB/s", meter.getSpeed())
    }

    @Test
    fun getSpeed_over1000Kb_formatsAsMbPerSec() {
        var bytes = 1_000_000L
        var time = 1000L
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { bytes },
            clockSupplier = { time },
        )

        meter.getSpeed() // baseline

        // 1 second later, 2.5 MB added (2560 KB)
        bytes += 2560L * 1024L
        time += 1000L

        assertEquals("2.50MB/s", meter.getSpeed())
    }

    @Test
    fun getSpeed_afterReset_restartsBaseline() {
        var bytes = 100_000L
        var time = 1000L
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { bytes },
            clockSupplier = { time },
        )

        meter.getSpeed()
        bytes += 500L * 1024L
        time += 1000L
        assertEquals("500KB/s", meter.getSpeed())

        meter.reset()
        assertEquals("0KB/s", meter.getSpeed())

        bytes += 1024L * 1024L
        time += 1000L
        assertEquals("1.00MB/s", meter.getSpeed())
    }

    @Test
    fun getSpeed_whenRxDeltaDecreases_doesNotCrashOrProduceNegative() {
        var bytes = 500_000L
        var time = 1000L
        val meter = TrafficSpeedMeter(
            rxBytesSupplier = { bytes },
            clockSupplier = { time },
        )

        meter.getSpeed()

        // Counter unexpectedly decreases
        bytes = 200_000L
        time += 1000L

        assertEquals("0KB/s", meter.getSpeed())
    }
}

