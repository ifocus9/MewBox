package com.mewbox.tv

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.mewbox.tv.player.PlayerTrackFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTrackFormatterTest {

    @Test
    fun formatTrackTitle_chineseSimplifiedSubtitle_matchesMewPlayerStyle() {
        val format = Format.Builder()
            .setLanguage("zh")
            .setLabel("Simplified")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_TEXT, 0)
        assertEquals("中文 (简体中文), Simplified, SRT", title)

        val details = PlayerTrackFormatter.formatTrackDetails(format, C.TRACK_TYPE_TEXT)
        assertNull(details)
    }

    @Test
    fun formatTrackTitle_portugueseBrazilSdh_matchesMewPlayerStyle() {
        val format = Format.Builder()
            .setLanguage("pt-BR")
            .setLabel("Brazil (SDH)")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_TEXT, 0)
        assertEquals("葡萄牙语 (巴西), Brazil (SDH), SRT", title)
    }

    @Test
    fun formatTrackTitle_traditionalChineseSubtitle() {
        val format = Format.Builder()
            .setLanguage("zh")
            .setLabel("Traditional")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_TEXT, 1)
        assertEquals("中文 (繁体中文), Traditional, SRT", title)
    }

    @Test
    fun formatTrackDetails_internalCues_doesNotExposeTechnicalNoise() {
        val format = Format.Builder()
            .setLanguage("zh")
            .setLabel("Simplified")
            .setSampleMimeType("application/x-media3-cues")
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_TEXT, 0)
        assertEquals("中文 (简体中文), Simplified", title)

        val details = PlayerTrackFormatter.formatTrackDetails(format, C.TRACK_TYPE_TEXT)
        assertNull(details)
    }

    @Test
    fun formatAudioTrack_stereoAac() {
        val format = Format.Builder()
            .setLanguage("en")
            .setLabel("Commentary")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(48000)
            .setAverageBitrate(192000)
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_AUDIO, 0)
        assertEquals("英语, Commentary", title)

        val details = PlayerTrackFormatter.formatTrackDetails(format, C.TRACK_TYPE_AUDIO)
        assertEquals("立体声 · 48 kHz · 192 kbps · AAC", details)
    }

    @Test
    fun formatVideoTrack_1080pHdr() {
        val format = Format.Builder()
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(60f)
            .setAverageBitrate(8000000)
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
            .build()

        val title = PlayerTrackFormatter.formatTrackTitle(format, C.TRACK_TYPE_VIDEO, 0)
        assertEquals("1080P · HDR10", title)

        val details = PlayerTrackFormatter.formatTrackDetails(format, C.TRACK_TYPE_VIDEO)
        assertEquals("1920x1080 · 8000 kbps · 60 fps · H.265", details)
    }
}

