package com.mewbox.tv

import com.mewbox.tv.bean.History
import com.mewbox.tv.player.Players
import com.mewbox.tv.ui.history.HistoryViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {

    @Test
    fun history_key_isConsistent() {
        val key = History.key("t4_site", "vod_123")
        assertEquals("t4_site_vod_123", key)
    }

    @Test
    fun history_formatProgress_withDuration() {
        val str = HistoryViewModel.formatProgress(position = 60_000L, duration = 120_000L)
        assertEquals("已看 50% (01:00 / 02:00)", str)
    }

    @Test
    fun history_formatProgress_withoutDuration() {
        val str = HistoryViewModel.formatProgress(position = 75_000L, duration = 0L)
        assertEquals("已看至 01:15", str)
    }

    @Test
    fun history_formatProgress_zeroPosition() {
        val str = HistoryViewModel.formatProgress(position = 0L, duration = 120_000L)
        assertEquals("刚刚观看", str)
    }

    @Test
    fun players_formatDuration_hours() {
        val str = Players.formatDuration(3665_000L) // 1小时1分5秒
        assertEquals("01:01:05", str)
    }

    @Test
    fun players_formatDuration_minutes() {
        val str = Players.formatDuration(125_000L) // 2分5秒
        assertEquals("02:05", str)
    }
}
