package com.mewbox.tv

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.mewbox.tv.player.Players
import com.mewbox.tv.player.exo.HttpEofRecoveryDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException

class PlayerSeekTest {

    @Test
    fun httpEofRecovery_isRecoverableEof_detectsExpectedErrors() {
        // 1. Direct exceptions
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(EOFException("Premature EOF")))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(ProtocolException("unexpected end of stream")))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(ProtocolException("unexpected eof")))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(SocketException("Connection reset by peer")))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(SocketTimeoutException("Read timed out")))

        // 2. Wrapped in multi-level causes (e.g. DataSourceException -> OkHttp / Network exception)
        val wrappedSocketEx = IOException("Wrapped read error", SocketException("Connection reset"))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(wrappedSocketEx))

        val wrappedEof = IOException("Wrapped EOF error", ProtocolException("unexpected end of stream"))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(wrappedEof))

        val deeplyNested = RuntimeException("Outer", IOException("Middle", SocketTimeoutException("Read timed out")))
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(deeplyNested))

        // 3. Unrecoverable errors
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(IOException("Disk full")))
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(IllegalArgumentException("Bad argument")))
    }

    @Test
    fun httpEofRecovery_remainingLength_calculations() {
        val unset = C.LENGTH_UNSET.toLong()
        assertEquals(unset, HttpEofRecoveryDataSource.remainingLength(unset, 1024L))
        assertEquals(700L, HttpEofRecoveryDataSource.remainingLength(1000L, 300L))
        assertEquals(0L, HttpEofRecoveryDataSource.remainingLength(1000L, 1000L))
        assertEquals(0L, HttpEofRecoveryDataSource.remainingLength(1000L, 1500L))
        assertEquals(1000L, HttpEofRecoveryDataSource.remainingLength(1000L, -50L))
    }

    @Test
    fun players_defaultUserAgent_isValidStandardBrowserUa() {
        assertTrue(Players.DEFAULT_USER_AGENT.isNotBlank())
        assertTrue(Players.DEFAULT_USER_AGENT.contains("Mozilla/5.0"))
    }

    @Test
    fun players_formatDuration_edgeCases() {
        assertEquals("00:00", Players.formatDuration(0L))
        assertEquals("00:00", Players.formatDuration(-5000L))
        assertEquals("00:05", Players.formatDuration(5_000L))
        assertEquals("09:59", Players.formatDuration(599_000L))
        assertEquals("01:00:00", Players.formatDuration(3600_000L))
        assertEquals("10:00:00", Players.formatDuration(36000_000L))
    }

    @Test
    fun players_inferMimeType_smartDetection() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            Players.inferMimeType("http://127.0.0.1:9978/m3u8-proxy?url=https%3A%2F%2Ffoo.com%2Fvideo.bin"),
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            Players.inferMimeType("https://example.com/live/stream.m3u8?token=xyz"),
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            Players.inferMimeType("https://example.com/manifest.mpd"),
        )
        assertEquals(
            MimeTypes.APPLICATION_RTSP,
            Players.inferMimeType("rtsp://stream.example.com/live"),
        )
        assertNull(Players.inferMimeType("https://example.com/video.mp4"))
    }

    @Test
    fun playerSeek_gestureCalculation_clamping() {
        val durationMs = 3600_000L // 1 hour
        val initialPosition = 1800_000L // 30 mins
        val fullSwipeMs = (durationMs / 15).coerceIn(90_000L, 300_000L) // 240,000L (4 mins)
        
        // Fast forward 25% swipe
        val deltaRatioXForward = 0.25f
        val deltaMsForward = (deltaRatioXForward * fullSwipeMs).toLong() // 60,000L (1 min)
        val targetMsForward = (initialPosition + deltaMsForward).coerceIn(0L, durationMs)
        assertEquals(1860_000L, targetMsForward)

        // Rewind past beginning
        val initialStart = 30_000L // 30s
        val deltaRatioXRewind = -0.5f // -120s
        val deltaMsRewind = (deltaRatioXRewind * fullSwipeMs).toLong()
        val targetMsRewind = (initialStart + deltaMsRewind).coerceIn(0L, durationMs)
        assertEquals(0L, targetMsRewind)

        // Forward past ending
        val initialEnd = 3580_000L
        val targetMsOver = (initialEnd + deltaMsForward).coerceIn(0L, durationMs)
        assertEquals(durationMs, targetMsOver)
    }

    @Test
    fun playerSeek_progressRatio_whenDurationZero() {
        val duration = 0L
        val currentPosition = 15_000L
        val progressRatio = if (duration > 0) {
            (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
        assertEquals(0f, progressRatio, 0.0001f)
    }
}
