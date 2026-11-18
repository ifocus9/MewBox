package com.mewbox.tv

import androidx.media3.common.C
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.PlayerTrack
import com.mewbox.tv.player.Players
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateResetAndDetailRetryTest {

    @Test
    fun buildStoppedState_clearsDirectUrlHeadersTitleAndTracks_keepsUserPrefs() {
        val sampleTrack = PlayerTrack(
            id = "track_1",
            name = "中文字幕",
            type = C.TRACK_TYPE_TEXT,
            isSelected = true,
            groupIndex = 0,
            trackIndex = 0,
        )
        val dirtyState = PlayerState(
            playbackState = PlaybackState.READY,
            isPlaying = true,
            currentPosition = 123_456L,
            duration = 6_000_000L,
            bufferedPosition = 300_000L,
            errorMessage = "之前的异常",
            playUrl = "https://dl.quark.example/video.mkv",
            headers = mapOf("User-Agent" to "CustomUA", "Cookie" to "secret=1"),
            title = "上一部网盘电影 - 正片",
            speed = 1.5f,
            resizeMode = 3,
            videoWidth = 3840,
            videoHeight = 2160,
            networkSpeed = "12.5MB/s",
            subtitleTracks = listOf(sampleTrack),
            audioTracks = listOf(sampleTrack.copy(id = "audio_1", type = C.TRACK_TYPE_AUDIO)),
            videoTracks = listOf(sampleTrack.copy(id = "video_1", type = C.TRACK_TYPE_VIDEO)),
            isSubtitleDisabled = true,
        )

        val stopped = Players.buildStoppedState(dirtyState)

        assertEquals(PlaybackState.IDLE, stopped.playbackState)
        assertFalse(stopped.isPlaying)
        assertEquals(0L, stopped.currentPosition)
        assertEquals(0L, stopped.duration)
        assertEquals(0L, stopped.bufferedPosition)
        assertNull(stopped.errorMessage)
        assertEquals("", stopped.playUrl)
        assertTrue(stopped.headers.isEmpty())
        assertEquals("", stopped.title)
        assertEquals(0, stopped.videoWidth)
        assertEquals(0, stopped.videoHeight)
        assertEquals("", stopped.networkSpeed)
        assertTrue(stopped.subtitleTracks.isEmpty())
        assertTrue(stopped.audioTracks.isEmpty())
        assertTrue(stopped.videoTracks.isEmpty())
        assertFalse(stopped.isSubtitleDisabled)

        // 用户播放倍速与画面填充偏好保持不变
        assertEquals(1.5f, stopped.speed, 0.0001f)
        assertEquals(3, stopped.resizeMode)
    }

    @Test
    fun buildErrorState_neverLeaksPreviousUrlOrHeadersWhenUrlEmptyOrChanged() {
        val previous = PlayerState(
            playbackState = PlaybackState.READY,
            isPlaying = true,
            playUrl = "https://old.example/stream.mkv",
            headers = mapOf("Referer" to "https://old.example/"),
            title = "旧影片",
            networkSpeed = "3MB/s",
        )

        val emptyUrlErr = Players.buildErrorState(previous, message = "播放地址为空", url = "")
        assertEquals(PlaybackState.ERROR, emptyUrlErr.playbackState)
        assertFalse(emptyUrlErr.isPlaying)
        assertEquals("播放地址为空", emptyUrlErr.errorMessage)
        assertEquals("", emptyUrlErr.playUrl)
        assertTrue(emptyUrlErr.headers.isEmpty())
        assertEquals("", emptyUrlErr.title)
        assertEquals("", emptyUrlErr.networkSpeed)

        val newUrlErr = Players.buildErrorState(previous, message = "网页地址不支持", url = "https://web.example/play.html")
        assertEquals("https://web.example/play.html", newUrlErr.playUrl)
        assertTrue(newUrlErr.headers.isEmpty())
        assertEquals("", newUrlErr.title)

        val sameUrlErr = Players.buildErrorState(previous, message = "读取超时", url = "https://old.example/stream.mkv")
        assertEquals("https://old.example/stream.mkv", sameUrlErr.playUrl)
        assertEquals(previous.headers, sameUrlErr.headers)
        assertEquals("旧影片", sameUrlErr.title)
    }

    @Test
    fun siteRepository_detailRetryPolicy_skipsSlowUpstreamTimeouts() {
        val t4Site = Site(key = "t4_panwebsharehj", name = "花卷[盘]", type = 4, api = "http://127.0.0.1:9988/t4/api/panwebsharehj")
        val nodeSite = Site(key = "nodejs_panwebsharehj", name = "花卷[盘]", type = 3, api = "node:/spider/panwebsharehj/3")
        val normalSite = Site(key = "cms_other", name = "普通站", type = 1, api = "https://example.com/api.php")

        assertTrue(SiteRepository.shouldRetryEmptyDetail(t4Site, elapsedMs = 200L, attempt = 1, maxAttempts = 3))
        assertTrue(SiteRepository.shouldRetryEmptyDetail(nodeSite, elapsedMs = 2_999L, attempt = 2, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryEmptyDetail(t4Site, elapsedMs = 3_000L, attempt = 1, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryEmptyDetail(t4Site, elapsedMs = 15_000L, attempt = 1, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryEmptyDetail(t4Site, elapsedMs = 100L, attempt = 3, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryEmptyDetail(normalSite, elapsedMs = 100L, attempt = 1, maxAttempts = 3))

        assertTrue(SiteRepository.shouldRetryDetailError(elapsedMs = 1_500L, attempt = 1, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryDetailError(elapsedMs = 10_000L, attempt = 1, maxAttempts = 3))
        assertFalse(SiteRepository.shouldRetryDetailError(elapsedMs = 500L, attempt = 3, maxAttempts = 3))
    }

    @Test
    fun siteRepository_hasPlayableDetail_detectsEmptyPlaceholderVod() {
        val placeholder = Result(
            list = listOf(
                Vod(vodId = "12345", vodName = "", vodPlayFrom = "无", vodPlayUrl = "", playFlags = emptyList()),
            ),
        )
        assertFalse(SiteRepository.hasPlayableDetail(Result()))
        assertFalse(SiteRepository.hasPlayableDetail(placeholder))

        val playable = Result(
            list = listOf(
                Vod(
                    vodId = "12345",
                    vodName = "测试影片",
                    playFlags = listOf(Flag("1080", listOf(Episode("第1集", "param1", 0)))),
                ),
            ),
        )
        assertTrue(SiteRepository.hasPlayableDetail(playable))

        val actionItem = Result(
            list = listOf(Vod(vodId = "openInternalWebsite", action = "openInternalWebsite")),
        )
        assertTrue(SiteRepository.hasPlayableDetail(actionItem))
    }
}
