package com.mewbox.tv

import com.mewbox.tv.player.Players
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIsoDetectionTest {

    @Test
    fun testIsoFileExtensionInUrl() {
        assertTrue(Players.isIsoFormat("https://pan.quark.cn/download/avatar.iso"))
        assertTrue(Players.isIsoFormat("http://192.168.1.100/movies/the_matrix.ISO"))
        assertTrue(Players.isIsoFormat("https://aliyun.com/file.iso?auth=abc123456&expire=1800"))
        assertTrue(Players.isIsoFormat("https://115.com/file.iso#section1"))
        assertTrue(Players.isIsoFormat("http://host/iso/movie_part1"))
    }

    @Test
    fun testIsoSchemeAndProtocol() {
        assertTrue(Players.isIsoFormat("webhtv-dvdiso://1001/longest"))
        assertTrue(Players.isIsoFormat("dvd://1"))
        assertTrue(Players.isIsoFormat("bd://1"))
    }

    @Test
    fun testIsoInEpisodeName() {
        assertTrue(Players.isIsoFormat("https://pds.quark.cn/download/stream?id=999", name = "阿凡达.1080p.BluRay.iso"))
        assertTrue(Players.isIsoFormat("https://example.com/video", name = "泰坦尼克号.ISO"))
        assertTrue(Players.isIsoFormat("https://example.com/video", name = "Inception.iso.part1"))
        assertTrue(Players.isIsoFormat("https://example.com/video", name = "Interstellar.iso 4K"))
    }

    @Test
    fun testIsoInFormat() {
        assertTrue(Players.isIsoFormat("https://example.com/stream", format = "iso"))
        assertTrue(Players.isIsoFormat("https://example.com/stream", format = "bluray"))
        assertTrue(Players.isIsoFormat("https://example.com/stream", format = "dvd"))
        assertTrue(Players.isIsoFormat("https://example.com/stream", format = "application/x-iso9660-image"))
    }

    @Test
    fun testNonIsoStreams() {
        assertFalse(Players.isIsoFormat("https://example.com/video.mp4", name = "正片.mp4"))
        assertFalse(Players.isIsoFormat("https://example.com/video.mkv", name = "第01集.mkv"))
        assertFalse(Players.isIsoFormat("https://example.com/live.m3u8", format = "hls"))
        assertFalse(Players.isIsoFormat("https://example.com/live/manifest.mpd", format = "dash"))
        assertFalse(Players.isIsoFormat("https://example.com/isolation_ward.mp4", name = "第02集"))
    }
}

