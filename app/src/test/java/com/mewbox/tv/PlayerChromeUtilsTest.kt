package com.mewbox.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChromeUtilsTest {

    @Test
    fun formatResolutionBadge_mapsStandardAndEdgeResolutions() {
        assertNull(formatResolutionBadge(0, 1080))
        assertNull(formatResolutionBadge(1920, 0))
        assertNull(formatResolutionBadge(-1, -1))

        assertEquals("4K", formatResolutionBadge(3840, 2160))
        assertEquals("4K", formatResolutionBadge(2160, 3840))
        assertEquals("4K", formatResolutionBadge(3840, 1600))
        assertEquals("2K", formatResolutionBadge(2560, 1440))
        assertEquals("1080P", formatResolutionBadge(1920, 1080))
        assertEquals("1080P", formatResolutionBadge(1080, 1920))
        assertEquals("1080P", formatResolutionBadge(1920, 800))
        assertEquals("720P", formatResolutionBadge(1280, 720))
        assertEquals("480P", formatResolutionBadge(854, 480))
        assertEquals("400×300", formatResolutionBadge(400, 300))
    }

    @Test
    fun formatSpeedAndZoomLabels_handleSingleAndTwoDecimals() {
        assertEquals("0.5x", formatSpeedLabel(0.5f))
        assertEquals("0.75x", formatSpeedLabel(0.75f))
        assertEquals("1.0x", formatSpeedLabel(1.0f))
        assertEquals("1.25x", formatSpeedLabel(1.25f))
        assertEquals("1.5x", formatSpeedLabel(1.5f))
        assertEquals("2.0x", formatSpeedLabel(2.0f))
        assertEquals("3.0x", formatSpeedLabel(3.0f))

        assertEquals("1.0x", formatZoomLabel(1.0f))
        assertEquals("1.25x", formatZoomLabel(1.25f))
        assertEquals("1.4x", formatZoomLabel(1.4f))
    }

    @Test
    fun isZoomModified_detectsThresholdAroundUnity() {
        assertFalse(isZoomModified(1.0f))
        assertFalse(isZoomModified(1.02f))
        assertFalse(isZoomModified(0.98f))
        assertTrue(isZoomModified(1.04f))
        assertTrue(isZoomModified(0.96f))
        assertTrue(isZoomModified(0.75f))
        assertTrue(isZoomModified(2.5f))
    }

    @Test
    fun playerAspectMode_mapsResizeModesWithFitFallback() {
        assertEquals(PlayerAspectMode.FIT, PlayerAspectMode.fromResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT))
        assertEquals(PlayerAspectMode.ZOOM, PlayerAspectMode.fromResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM))
        assertEquals(PlayerAspectMode.STRETCH, PlayerAspectMode.fromResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL))
        assertEquals(PlayerAspectMode.FIT, PlayerAspectMode.fromResizeMode(999))
    }
}
