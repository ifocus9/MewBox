package com.mewbox.tv.api

import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import com.mewbox.tv.api.tmdb.bean.TmdbResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbClientTest {

    @Test
    fun testCleanTitleEpisodes() {
        assertEquals("复仇者联盟4", TmdbClient.cleanTitle("复仇者联盟4 更新至第3集"))
        assertEquals("三体", TmdbClient.cleanTitle("三体 全30集"))
        assertEquals("狂飙", TmdbClient.cleanTitle("狂飙 共39集"))
        assertEquals("海贼王", TmdbClient.cleanTitle("海贼王 第1000集"))
    }

    @Test
    fun testCleanTitleQualityAndLanguage() {
        assertEquals("流浪地球2", TmdbClient.cleanTitle("流浪地球2 4K/HDR国语版"))
        assertEquals("奥本海默", TmdbClient.cleanTitle("奥本海默 1080P中字"))
        assertEquals("星际穿越", TmdbClient.cleanTitle("星际穿越 BluRay 2160p HDR"))
    }

    @Test
    fun testCleanTitleBracketsAndSuffixes() {
        assertEquals("陈情令", TmdbClient.cleanTitle("陈情令（完结）粤语"))
        assertEquals("庆余年 第二季", TmdbClient.cleanTitle("庆余年 第二季 (已完结)"))
    }

    @Test
    fun testImageUrl() {
        val url = TmdbClient.imageUrl(null, "/abc.jpg", "w780")
        assertNotNull(url)
        assertTrue(url.endsWith("/w780/abc.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", url)

        val customUrl = TmdbClient.imageUrl("https://mycdn.example.com/t/p", "/def.png", "original")
        assertEquals("https://mycdn.example.com/t/p/original/def.png", customUrl)
    }

    @Test
    fun testNormalizeApiBase() {
        assertEquals("https://api.tmdb.org", TmdbClient.normalizeApiBase(null))
        assertEquals("https://api.tmdb.org", TmdbClient.normalizeApiBase(""))
        assertEquals("https://tmdb.example.com", TmdbClient.normalizeApiBase("tmdb.example.com"))
        assertEquals("https://tmdb.example.com", TmdbClient.normalizeApiBase("https://tmdb.example.com/"))
        assertEquals("https://tmdb.example.com", TmdbClient.normalizeApiBase("https://tmdb.example.com/3/"))
        assertEquals("https://tmdb.example.com", TmdbClient.normalizeApiBase("https://tmdb.example.com/3"))
    }

    @Test
    fun testNormalizeImageBase() {
        assertEquals("https://image.tmdb.org/t/p", TmdbClient.normalizeImageBase(null))
        assertEquals("https://image.tmdb.org/t/p", TmdbClient.normalizeImageBase(""))
        assertEquals("https://image.tmdb.org/t/p", TmdbClient.normalizeImageBase("https://image.tmdb.org"))
        assertEquals("https://cdn.example.com/t/p", TmdbClient.normalizeImageBase("cdn.example.com/t/p/"))
    }

    @Test
    fun testBearerTokenDetectionAndExtraction() {
        // v3 32-character hex key
        val v3Key = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        assertFalse(TmdbClient.isBearerToken(v3Key))

        // v4 JWT Read Access Token (starts with ey...)
        val v4Token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJmMTkyZDFkZWRk...very_long_token"
        assertTrue(TmdbClient.isBearerToken(v4Token))
        assertEquals(v4Token, TmdbClient.extractBearerToken(v4Token))

        // Token with Bearer prefix
        val bearerToken = "Bearer $v4Token"
        assertTrue(TmdbClient.isBearerToken(bearerToken))
        assertEquals(v4Token, TmdbClient.extractBearerToken(bearerToken))
    }

    @Test
    fun testApplyAuthApiKeyVsToken() {
        val urlBuilder = "https://api.tmdb.org/3/test".toHttpUrlOrNull()!!.newBuilder()
        val reqBuilder = Request.Builder()

        // 1. Test v3 Key: sets query parameter, no Authorization header
        TmdbClient.applyAuth(reqBuilder, urlBuilder, "1234567890abcdef1234567890abcdef")
        val req1 = reqBuilder.url(urlBuilder.build()).build()
        assertEquals("1234567890abcdef1234567890abcdef", req1.url.queryParameter("api_key"))
        assertNull(req1.header("Authorization"))

        // 2. Test v4 Token: sets Authorization Bearer header, no query parameter
        val tokenUrlBuilder = "https://api.tmdb.org/3/test".toHttpUrlOrNull()!!.newBuilder()
        val tokenReqBuilder = Request.Builder()
        val jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        TmdbClient.applyAuth(tokenReqBuilder, tokenUrlBuilder, jwtToken)
        val req2 = tokenReqBuilder.url(tokenUrlBuilder.build()).build()
        assertNull(req2.url.queryParameter("api_key"))
        assertEquals("Bearer $jwtToken", req2.header("Authorization"))
    }

    @Test
    fun testPickBestMatch() {
        val media1 = TmdbMedia(
            id = 101,
            title = "流浪地球2",
            popularity = 85.0,
        )
        val media2 = TmdbMedia(
            id = 102,
            title = "流浪地球",
            popularity = 40.0,
        )
        val result = TmdbResult(results = listOf(media2, media1))
        val picked = TmdbClient.pickBestMatch("流浪地球2 4K国语", result)
        assertNotNull(picked)
        assertEquals(101, picked!!.id)
        assertEquals("流浪地球2", picked.displayTitle)
    }

    /**
     * 复现「野兽赛跑」这类站源译名与 TMDB 中文名完全不同的场景：
     * 严格匹配会被 0.2 阈值判为无匹配，但兜底必须能选出带海报的候选。
     */
    @Test
    fun testPickFallbackMatchForDifferentChineseTitle() {
        val person = TmdbMedia(id = 1, rawMediaType = "person", name = "某演员", popularity = 900.0)
        val noImage = TmdbMedia(id = 202, rawMediaType = "movie", title = "无关影片", popularity = 3.0)
        val matched = TmdbMedia(
            id = 201,
            rawMediaType = "movie",
            title = "残酷兽竞",
            originalTitle = "Corrida dos Bichos",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            popularity = 6.0,
            voteCount = 120,
        )
        val result = TmdbResult(results = listOf(person, matched, noImage))

        assertNull(TmdbClient.pickBestMatch("野兽赛跑", result))
        val fallback = TmdbClient.pickFallbackMatch("野兽赛跑", result)
        assertNotNull(fallback)
        assertEquals(201, fallback!!.id)
        assertEquals("残酷兽竞", fallback.displayTitle)
    }

    @Test
    fun testPickFallbackMatchReturnsNullWhenNoMediaCandidates() {
        val result = TmdbResult(
            results = listOf(
                TmdbMedia(id = 1, rawMediaType = "person", name = "某演员", popularity = 100.0),
            )
        )
        assertNull(TmdbClient.pickFallbackMatch("任意片名", result))
        assertNull(TmdbClient.pickFallbackMatch("任意片名", TmdbResult()))
    }
}
