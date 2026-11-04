package com.mewbox.tv

import androidx.media3.common.C
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.PlayerTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackTest {

    @Test
    fun playerTrack_typeChecks_areAccurate() {
        val textTrack = PlayerTrack(
            id = "3_0_0",
            name = "简体中文",
            type = C.TRACK_TYPE_TEXT,
            isSelected = true,
            groupIndex = 0,
            trackIndex = 0,
            language = "zh",
            details = "SRT",
        )
        assertTrue(textTrack.isText)
        assertFalse(textTrack.isAudio)
        assertFalse(textTrack.isVideo)

        val audioTrack = PlayerTrack(
            id = "1_0_0",
            name = "国语",
            type = C.TRACK_TYPE_AUDIO,
            isSelected = false,
            groupIndex = 0,
            trackIndex = 0,
            language = "zh",
            details = "5.1 声道 · 48 kHz",
        )
        assertFalse(audioTrack.isText)
        assertTrue(audioTrack.isAudio)
        assertFalse(audioTrack.isVideo)

        val videoTrack = PlayerTrack(
            id = "2_0_0",
            name = "1080P",
            type = C.TRACK_TYPE_VIDEO,
            isSelected = true,
            groupIndex = 0,
            trackIndex = 0,
            details = "1920x1080 · 5000 kbps",
        )
        assertFalse(videoTrack.isText)
        assertFalse(videoTrack.isAudio)
        assertTrue(videoTrack.isVideo)
    }

    @Test
    fun playerState_holdsTrackListsProperly() {
        val sub = PlayerTrack(
            id = "sub_1",
            name = "英文",
            type = C.TRACK_TYPE_TEXT,
            isSelected = false,
            groupIndex = 0,
            trackIndex = 0,
        )
        val audio = PlayerTrack(
            id = "audio_1",
            name = "原声",
            type = C.TRACK_TYPE_AUDIO,
            isSelected = true,
            groupIndex = 0,
            trackIndex = 0,
        )

        val state = PlayerState(
            subtitleTracks = listOf(sub),
            audioTracks = listOf(audio),
            isSubtitleDisabled = true,
        )

        assertEquals(1, state.subtitleTracks.size)
        assertEquals(1, state.audioTracks.size)
        assertTrue(state.videoTracks.isEmpty())
        assertTrue(state.isSubtitleDisabled)
    }

    @Test
    fun inferMimeType_resolvesHanxiaoquanProxyM3u8Url() {
        val userUrl = "http://127.0.0.1:9988/spider/hanxiaoquan/3/proxy/hxq/aHR0cDovL3BpY2NjLmNkbi41MXRvdXhpYW5nLmNvbS9tM3U4LzRkY284Z2xYc1JsUFk2Y3V3TTY5TUFsU0pFT2hXSWpZc3hBU2xrX2oubTN1OD9waWQ9MncxNmNmYlQ2c2xNZHg0cTZneDJJT3NlV3RpWjVxaVk3MiUyQjgzTyUyQm5zN2MlM0QmdXRrPXVfUFpNUFBZNG5URGFuaXRxZWlBaWomdWs9amwzWE5ab3U2azI3MmVUMkIyaXExdVlVbVJqNXM4SzJ3WSUyRm9CMXN4c2dvJTNEJnNwbD0yMTMmdD0xNzkzNjEyNTEyJnNpZ249Y2I3NzljZTQ5Yjc2MGQ1M2VmNmJlZGZkMjk1NDUxMWImaXA9MTcxLjIyMy4yMDkuMjQ4"
        val mimeType = com.mewbox.tv.player.Players.inferMimeType(userUrl)
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun inferMimeType_handlesStandardFormatsAndFallbacks() {
        assertEquals(
            androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            com.mewbox.tv.player.Players.inferMimeType("http://example.com/live.m3u8")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            com.mewbox.tv.player.Players.inferMimeType("http://127.0.0.1:9988/m3u8-proxy?url=abc")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            com.mewbox.tv.player.Players.inferMimeType("http://example.com/play?id=123", format = "m3u8")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.APPLICATION_MPD,
            com.mewbox.tv.player.Players.inferMimeType("http://example.com/stream.mpd")
        )
        assertEquals(
            null,
            com.mewbox.tv.player.Players.inferMimeType("http://example.com/movie.mp4")
        )
    }
}

