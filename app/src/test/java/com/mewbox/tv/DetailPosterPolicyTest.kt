package com.mewbox.tv

import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbImageItem
import com.mewbox.tv.api.tmdb.bean.TmdbImages
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.detail.detailPosterUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DetailPosterPolicyTest {

    @Test
    fun `when tmdb images matched, carousel excludes original vodPic`() {
        val vod = Vod(
            vodId = "1",
            vodName = "测试影片",
            vodPic = "https://site.example.com/original-poster.jpg",
        )
        val tmdbDetail = TmdbDetail(
            id = 100,
            backdropPath = "/backdrop.jpg",
            posterPath = "/poster.jpg",
            images = TmdbImages(
                backdrops = listOf(TmdbImageItem(filePath = "/bd1.jpg", voteAverage = 8.0)),
                posters = listOf(TmdbImageItem(filePath = "/p1.jpg", voteAverage = 7.5)),
            ),
        )

        val urls = detailPosterUrls(
            vod = vod,
            tmdbDetail = tmdbDetail,
            tmdbImageBase = "https://image.tmdb.org/t/p",
        )

        assertFalse(urls.contains(vod.vodPic))
        assertEquals(
            listOf(
                "https://image.tmdb.org/t/p/w1280/bd1.jpg",
                "https://image.tmdb.org/t/p/w780/p1.jpg",
            ),
            urls,
        )
    }

    @Test
    fun `when only quick tmdb poster matched, carousel excludes original vodPic`() {
        val vod = Vod(
            vodId = "1",
            vodName = "测试影片",
            vodPic = "https://site.example.com/original-poster.jpg",
        )

        val urls = detailPosterUrls(
            vod = vod,
            tmdbDetail = null,
            tmdbImageBase = "https://image.tmdb.org/t/p",
            quickBackdrop = null,
            quickPoster = "https://image.tmdb.org/t/p/w780/quick-poster.jpg",
        )

        assertEquals(listOf("https://image.tmdb.org/t/p/w780/quick-poster.jpg"), urls)
    }

    @Test
    fun `when no tmdb images matched, carousel falls back to original vodPic`() {
        val vod = Vod(
            vodId = "1",
            vodName = "测试影片",
            vodPic = "https://site.example.com/original-poster.jpg",
        )

        val urls = detailPosterUrls(
            vod = vod,
            tmdbDetail = null,
            tmdbImageBase = "https://image.tmdb.org/t/p",
        )

        assertEquals(listOf("https://site.example.com/original-poster.jpg"), urls)
    }
}
