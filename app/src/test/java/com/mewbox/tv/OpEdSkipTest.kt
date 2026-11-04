package com.mewbox.tv

import com.mewbox.tv.bean.History
import com.mewbox.tv.player.Players
import com.mewbox.tv.ui.detail.DetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpEdSkipTest {

    @Test
    fun getOpEdLimit_matchesFongMiPolicy() {
        // 视频时长小于15分钟 -> 上限 3 分钟 (180,000ms)
        val shortDuration = 10 * 60 * 1000L
        assertEquals(3 * 60 * 1000L, DetailViewModel.getOpEdLimit(shortDuration))

        // 视频时长在15到30分钟之间 -> 上限 6 分钟 (360,000ms)
        val mediumDuration = 24 * 60 * 1000L
        assertEquals(6 * 60 * 1000L, DetailViewModel.getOpEdLimit(mediumDuration))

        // 视频时长大于30分钟 -> 上限 10 分钟 (600,000ms)
        val longDuration = 45 * 60 * 1000L
        assertEquals(10 * 60 * 1000L, DetailViewModel.getOpEdLimit(longDuration))
    }

    @Test
    fun openingStartPosition_skipsIntroWhenResumeIsBeforeOpening() {
        val opening = 90_000L // 片头 1分30秒

        // 新起播 (startPosition = 0) -> 跳到片头
        val startFromBeginning = 0L
        val effectiveStart1 = if (opening > 0L) maxOf(startFromBeginning, opening) else startFromBeginning
        assertEquals(90_000L, effectiveStart1)

        // 历史记录在片头内 (startPosition = 30_000) -> 同样跳到片头
        val resumeInOpening = 30_000L
        val effectiveStart2 = if (opening > 0L) maxOf(resumeInOpening, opening) else resumeInOpening
        assertEquals(90_000L, effectiveStart2)

        // 历史记录已超过片头 (startPosition = 500_000) -> 保持历史进度续播
        val resumeAfterOpening = 500_000L
        val effectiveStart3 = if (opening > 0L) maxOf(resumeAfterOpening, opening) else resumeAfterOpening
        assertEquals(500_000L, effectiveStart3)
    }

    @Test
    fun endingSkip_triggersWhenWithinRemainingEndingTime() {
        val duration = 45 * 60 * 1000L // 45分钟 (2,700,000ms)
        val ending = 2 * 60 * 1000L    // 片尾 2分钟 (120,000ms)

        // 播放到 42 分钟 (剩余 3 分钟) -> 未达到片尾
        val pos1 = 42 * 60 * 1000L
        val remaining1 = duration - pos1
        assertFalse(remaining1 <= ending)

        // 播放到 43 分钟 (剩余 2 分钟整) -> 触发片尾跳过
        val pos2 = 43 * 60 * 1000L
        val remaining2 = duration - pos2
        assertTrue(remaining2 <= ending)

        // 播放到 44 分钟 (剩余 1 分钟) -> 处于片尾区域
        val pos3 = 44 * 60 * 1000L
        val remaining3 = duration - pos3
        assertTrue(remaining3 <= ending)
    }

    @Test
    fun history_containsOpeningAndEndingFields() {
        val history = History(
            key = "site_1_vod_1",
            siteKey = "site_1",
            siteName = "站点1",
            vodId = "vod_1",
            vodName = "测试视频",
            vodPic = "pic.jpg",
            vodFlag = "线路1",
            episodeName = "第01集",
            episodeUrl = "http://test.m3u8",
            episodeIndex = 0,
            position = 10000L,
            duration = 60000L,
            opening = 90000L,
            ending = 120000L,
        )

        assertEquals(90000L, history.opening)
        assertEquals(120000L, history.ending)
        assertEquals("01:30", Players.formatDuration(history.opening))
        assertEquals("02:00", Players.formatDuration(history.ending))
    }
}

