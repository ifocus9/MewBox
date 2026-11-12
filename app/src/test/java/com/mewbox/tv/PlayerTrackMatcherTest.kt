package com.mewbox.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.mewbox.tv.bean.TrackPreference
import com.mewbox.tv.player.PlayerTrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackMatcherTest {

    @Test
    fun describeFormat_capturesEssentialFormatFields() {
        val format = Format.Builder()
            .setId("sub_1")
            .setLabel("简体中文")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val desc = PlayerTrackMatcher.describeFormat(format)
        assertTrue(desc.contains("id=sub_1"))
        assertTrue(desc.contains("label=简体中文"))
        assertTrue(desc.contains("lang=zh"))
        assertTrue(desc.contains("mime=${MimeTypes.APPLICATION_SUBRIP}"))
    }

    @Test
    fun findBestMatch_matchesExactFormatWithHighScore() {
        val formatZh = Format.Builder()
            .setId("1")
            .setLabel("简体中文")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()
        val formatEn = Format.Builder()
            .setId("2")
            .setLabel("English")
            .setLanguage("en")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()

        val groupZh = Tracks.Group(TrackGroup("text_zh", formatZh), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val groupEn = Tracks.Group(TrackGroup("text_en", formatEn), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(groupZh, groupEn))

        val preference = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_TEXT,
            name = "简体中文",
            format = PlayerTrackMatcher.describeFormat(formatZh),
            language = "zh",
            label = "简体中文",
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, preference)
        assertNotNull(match)
        assertEquals(0, match!!.groupIndex)
        assertEquals(0, match.trackIndex)
        assertEquals("1", match.format.id)
        assertTrue(match.score >= 100)
    }

    @Test
    fun findBestMatch_matchesWhenSubtitleIdChangesAcrossEpisodes() {
        // 第一集中字幕 id 为 "sub-100"，下一集中字幕 id 变为了 "3"，但 label 为 "简体中文"，language 为 "zh"
        val nextEpisodeFormat = Format.Builder()
            .setId("3")
            .setLabel("简体中文")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()
        val nextEpisodeOther = Format.Builder()
            .setId("4")
            .setLabel("English")
            .setLanguage("en")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()

        val groupZh = Tracks.Group(TrackGroup("text_zh_ep2", nextEpisodeFormat), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val groupEn = Tracks.Group(TrackGroup("text_en_ep2", nextEpisodeOther), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(groupZh, groupEn))

        // 上一集保存的偏好，原 id 是 sub-100
        val previousPref = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_TEXT,
            name = "简体中文",
            format = "id=sub-100,label=简体中文,lang=zh,mime=${MimeTypes.TEXT_VTT}",
            language = "zh",
            label = "简体中文",
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, previousPref)
        assertNotNull(match)
        assertEquals(0, match!!.groupIndex)
        assertEquals(0, match.trackIndex)
        assertEquals("3", match.format.id)
        // 虽然 format 全等比对失败，但由于 label 和 language 匹配，得分依然很高
        assertTrue(match.score >= 80)
    }

    @Test
    fun findBestMatch_distinguishesSimplifiedAndTraditionalChinese() {
        val formatSimp = Format.Builder()
            .setId("1")
            .setLabel("简中内嵌")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val formatTrad = Format.Builder()
            .setId("2")
            .setLabel("繁中内嵌")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val groupSimp = Tracks.Group(TrackGroup("text_simp", formatSimp), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val groupTrad = Tracks.Group(TrackGroup("text_trad", formatTrad), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(groupSimp, groupTrad))

        val simpPref = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_TEXT,
            name = "简中内嵌",
            language = "zh",
            label = "简中内嵌",
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, simpPref)
        assertNotNull(match)
        assertEquals(0, match!!.groupIndex)
        assertEquals(0, match.trackIndex)
        assertEquals("1", match.format.id)
    }

    @Test
    fun findBestMatch_disabledReturnsNull() {
        val formatZh = Format.Builder()
            .setId("1")
            .setLabel("中文")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .build()
        val trackGroup = TrackGroup("group_text", formatZh)
        val group = Tracks.Group(trackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(group))

        val disabledPref = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_TEXT,
            name = "关闭字幕",
            format = TrackPreference.DISABLED_FORMAT,
            isDisabled = true,
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, disabledPref)
        assertNull(match)
    }

    @Test
    fun findBestMatch_matchesAudioTracksByLanguageAndLabel() {
        val audioMandarin = Format.Builder()
            .setId("a1")
            .setLabel("国语普通话")
            .setLanguage("zh")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val audioCantonese = Format.Builder()
            .setId("a2")
            .setLabel("粤语原声")
            .setLanguage("yue")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()

        val groupMandarin = Tracks.Group(TrackGroup("audio_mandarin", audioMandarin), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val groupCantonese = Tracks.Group(TrackGroup("audio_cantonese", audioCantonese), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(groupMandarin, groupCantonese))

        val cantonesePref = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_AUDIO,
            name = "粤语原声",
            language = "yue",
            label = "粤语原声",
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, cantonesePref)
        assertNotNull(match)
        assertEquals(1, match!!.groupIndex)
        assertEquals(0, match.trackIndex)
        assertEquals("a2", match.format.id)
    }

    @Test
    fun findBestMatch_matchesVideoTracksByResolution() {
        val video720p = Format.Builder()
            .setId("v720")
            .setWidth(1280)
            .setHeight(720)
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build()
        val video1080p = Format.Builder()
            .setId("v1080")
            .setWidth(1920)
            .setHeight(1080)
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build()

        val group720 = Tracks.Group(TrackGroup("video_720", video720p), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val group1080 = Tracks.Group(TrackGroup("video_1080", video1080p), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val tracks = Tracks(listOf(group720, group1080))

        val pref1080p = TrackPreference(
            key = "test_key",
            type = C.TRACK_TYPE_VIDEO,
            name = "1080P",
            width = 1920,
            height = 1080,
        )

        val match = PlayerTrackMatcher.findBestMatch(tracks, pref1080p)
        assertNotNull(match)
        assertEquals(1, match!!.groupIndex)
        assertEquals(0, match.trackIndex)
        assertEquals("v1080", match.format.id)
    }
}
