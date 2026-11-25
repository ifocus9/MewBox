package com.mewbox.tv

import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbEpisode
import com.mewbox.tv.api.tmdb.bean.TmdbImageItem
import com.mewbox.tv.api.tmdb.bean.TmdbImages
import com.mewbox.tv.api.tmdb.bean.TmdbSeasonSummary
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.util.TmdbEpisodeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbEpisodeMatcherTest {

    @Test
    fun `single season show uses numeric badge and episode still`() {
        val detail = TmdbDetail(
            id = 1001,
            rawMediaType = "tv",
            name = "今天是太阳雨",
            numberOfSeasons = 1,
            backdropPath = "/show_bd.jpg",
            seasons = listOf(
                TmdbSeasonSummary(id = 10, seasonNumber = 0, name = "Specials", episodeCount = 2),
                TmdbSeasonSummary(id = 11, seasonNumber = 1, name = "第 1 季", episodeCount = 24),
            ),
        )
        val season1Episodes = listOf(
            TmdbEpisode(id = 1, seasonNumber = 1, episodeNumber = 1, name = "第一名的偏差值", stillPath = "/ep1.jpg"),
            TmdbEpisode(id = 2, seasonNumber = 1, episodeNumber = 2, name = "早起的鸟儿们", stillPath = "/ep2.jpg"),
        )
        val sourceEpisodes = listOf(
            Episode(name = "第1集", url = "u1", index = 0),
            Episode(name = "第2集", url = "u2", index = 1),
        )

        val cards = TmdbEpisodeMatcher.resolveCards(
            episodes = sourceEpisodes,
            fallbackDisplayNames = sourceEpisodes.map { it.name },
            vodName = "今天是太阳雨",
            flagName = "百度#1",
            tmdbDetail = detail,
            tmdbEpisodesBySeason = mapOf(1 to season1Episodes),
            imageBase = "https://image.tmdb.org/t/p",
        )

        assertEquals(2, cards.size)
        assertEquals("1", cards[0].badgeText)
        assertEquals("https://image.tmdb.org/t/p/w300/ep1.jpg", cards[0].stillUrl)
        assertEquals("第一名的偏差值", cards[0].title)
        assertTrue(cards[0].matchedTmdb)

        assertEquals("2", cards[1].badgeText)
        assertEquals("https://image.tmdb.org/t/p/w300/ep2.jpg", cards[1].stillUrl)
        assertEquals("早起的鸟儿们", cards[1].title)
        assertTrue(cards[1].matchedTmdb)
    }

    @Test
    fun `multi season show uses SxEy badge and excludes NCED extra`() {
        val detail = TmdbDetail(
            id = 2001,
            rawMediaType = "tv",
            name = "葬送的芙莉莲",
            numberOfSeasons = 2,
            backdropPath = "/frieren_bd.jpg",
            seasons = listOf(
                TmdbSeasonSummary(id = 20, seasonNumber = 1, name = "第 1 季", episodeCount = 28),
                TmdbSeasonSummary(id = 21, seasonNumber = 2, name = "第 2 季", episodeCount = 10),
            ),
        )
        val season1Episodes = listOf(
            TmdbEpisode(id = 101, seasonNumber = 1, episodeNumber = 2, name = "也不一定要是魔法…", stillPath = "/s1e2.jpg"),
        )
        val sourceEpisodes = listOf(
            Episode(name = "NCED.mkv【393.63 MB】", url = "u0", index = 0),
            Episode(name = "02.mkv【520 MB】", url = "u1", index = 1),
            Episode(name = "第1集 预告.mp4", url = "u2", index = 2),
        )

        val cards = TmdbEpisodeMatcher.resolveCards(
            episodes = sourceEpisodes,
            fallbackDisplayNames = sourceEpisodes.map { it.name },
            vodName = "葬送的芙莉莲",
            flagName = "夸克#1",
            tmdbDetail = detail,
            tmdbEpisodesBySeason = mapOf(1 to season1Episodes),
            imageBase = "https://image.tmdb.org/t/p",
        )

        // NCED 特典不匹配、无角标、无单集剧照、保留原名
        assertNull(cards[0].badgeText)
        assertNull(cards[0].stillUrl)
        assertEquals("NCED.mkv【393.63 MB】", cards[0].title)
        assertFalse(cards[0].matchedTmdb)

        // 第 2 集命中多季角标 S1E2
        assertEquals("S1E2", cards[1].badgeText)
        assertEquals("https://image.tmdb.org/t/p/w300/s1e2.jpg", cards[1].stillUrl)
        assertEquals("也不一定要是魔法…", cards[1].title)
        assertTrue(cards[1].matchedTmdb)

        // 预告片也视为非正片特典
        assertNull(cards[2].badgeText)
        assertNull(cards[2].stillUrl)
        assertFalse(cards[2].matchedTmdb)
    }

    @Test
    fun `title digits like 庆余年2 are not misparsed as episode numbers`() {
        val episodes = listOf(
            Episode(name = "庆余年2 - 01.1080p.mkv", url = "u1", index = 0),
            Episode(name = "庆余年2 - 02.1080p.mkv", url = "u2", index = 1),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 2, vodName = "庆余年2")
        assertEquals(2, refs[0]?.season)
        assertEquals(1, refs[0]?.episode)
        assertEquals(2, refs[1]?.season)
        assertEquals(2, refs[1]?.episode)
    }

    @Test
    fun `chinese title directly followed by episode digits parses cleanly`() {
        val episodes = listOf(
            Episode(name = "狂飙01.mp4", url = "u1", index = 0),
            Episode(name = "狂飙02.mp4", url = "u2", index = 1),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 1, vodName = "狂飙")
        assertEquals(1, refs[0]?.episode)
        assertEquals(2, refs[1]?.episode)
    }

    @Test
    fun `mixed formats and tech noise with brackets or underscores resolve cleanly`() {
        val episodes = listOf(
            Episode(name = "01.mkv", url = "u1", index = 0),
            Episode(name = "葬送的芙莉莲 02.mkv", url = "u2", index = 1),
            Episode(name = "[1080P] 03.mkv", url = "u3", index = 2),
            Episode(name = "2160p_04.mkv", url = "u4", index = 3),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 1, vodName = "葬送的芙莉莲")
        assertEquals(listOf(1, 2, 3, 4), refs.map { it?.episode })
    }

    @Test
    fun `leading year bracket does not block following episode bracket`() {
        val episodes = listOf(
            Episode(name = "[2026][01].mkv", url = "u1", index = 0),
            Episode(name = "[2024] [02] 标题.mkv", url = "u2", index = 1),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 1)
        assertEquals(1, refs[0]?.episode)
        assertEquals(2, refs[1]?.episode)
    }

    @Test
    fun `v2 revision suffixes and episode ranges are parsed properly`() {
        val episodes = listOf(
            Episode(name = "01v2.mkv", url = "u1", index = 0),
            Episode(name = "S01E02v2.mkv", url = "u2", index = 1),
            Episode(name = "EP03v3.mkv", url = "u3", index = 2),
            Episode(name = "第04-05集.mkv", url = "u4", index = 3),
            Episode(name = "06-07集.mkv", url = "u5", index = 4),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 1)
        assertEquals(listOf(1, 2, 3, 4, 6), refs.map { it?.episode })
    }

    @Test
    fun `chinese season and episode numbers and strict flag matching`() {
        assertEquals(2, TmdbEpisodeMatcher.detectDefaultSeason("庆余年 第二季"))
        assertEquals(3, TmdbEpisodeMatcher.detectDefaultSeason("某剧", "Season 3"))
        // 线路名如「云盘 S2」不得误判为第 2 季
        assertEquals(1, TmdbEpisodeMatcher.detectDefaultSeason("今天是太阳雨", "云盘 S2"))

        val episodes = listOf(
            Episode(name = "第十二集", url = "u12", index = 0),
            Episode(name = "第一季第3集", url = "u3", index = 1),
            Episode(name = "E 1999", url = "u1999", index = 2),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 2)
        assertEquals(2, refs[0]?.season)
        assertEquals(12, refs[0]?.episode)
        assertFalse(refs[0]?.explicitSeason ?: true)

        assertEquals(1, refs[1]?.season)
        assertEquals(3, refs[1]?.episode)
        assertTrue(refs[1]?.explicitSeason ?: false)

        // 单字母 E 后跟空格与年份数字不得当成第 1999 集
        assertNull(refs[2])
    }

    @Test
    fun `soft extra keywords do not false positive on show title or numbered episode`() {
        val detail = TmdbDetail(
            id = 5001,
            rawMediaType = "tv",
            name = "幕后之王",
            numberOfSeasons = 1,
            seasons = listOf(TmdbSeasonSummary(id = 50, seasonNumber = 1, episodeCount = 46)),
        )
        val episodes = listOf(
            Episode(name = "幕后之王 第01集", url = "u1", index = 0),
            Episode(name = "第05集 幕后黑手.mkv", url = "u5", index = 1),
            Episode(name = "第06集 抢先版.mp4", url = "u6", index = 2),
            Episode(name = "幕后采访.mkv", url = "ux1", index = 3),
            Episode(name = "彩蛋.mkv", url = "ux2", index = 4),
            Episode(name = "OP01.mkv", url = "ux3", index = 5),
            Episode(name = "ED.mp4", url = "ux4", index = 6),
        )
        val refs = TmdbEpisodeMatcher.parseEpisodeRefs(episodes, defaultSeason = 1, vodName = detail.name.orEmpty())
        assertEquals(1, refs[0]?.episode)
        assertEquals(5, refs[1]?.episode)
        assertEquals(6, refs[2]?.episode)
        assertNull(refs[3])
        assertNull(refs[4])
        assertNull(refs[5])
        assertNull(refs[6])
    }

    @Test
    fun `continuous episode numbering maps before S2 load and resolves required seasons`() {
        val detail = TmdbDetail(
            id = 3001,
            rawMediaType = "tv",
            name = "测试番剧",
            numberOfSeasons = 2,
            seasons = listOf(
                TmdbSeasonSummary(id = 30, seasonNumber = 1, episodeCount = 28),
                TmdbSeasonSummary(id = 31, seasonNumber = 2, episodeCount = 12),
            ),
        )
        val episodes = listOf(
            Episode(name = "第28集", url = "u28", index = 0),
            Episode(name = "第29集", url = "u29", index = 1),
        )
        val required = TmdbEpisodeMatcher.resolveRequiredSeasons(
            tmdbDetail = detail,
            vodName = "测试番剧",
            flagName = "夸克",
            episodes = episodes,
        )
        assertEquals(listOf(1, 2), required)

        // 即使第 2 季尚未拉到数据，第 29 集也直接归为 S2E1 而非闪烁 S1E29
        val loadingCards = TmdbEpisodeMatcher.resolveCards(
            episodes = episodes,
            fallbackDisplayNames = episodes.map { it.name },
            vodName = "测试番剧",
            flagName = "夸克",
            tmdbDetail = detail,
            tmdbEpisodesBySeason = emptyMap(),
            imageBase = "https://image.tmdb.org/t/p",
        )
        assertEquals("S2E1", loadingCards[1].badgeText)

        val loadedCards = TmdbEpisodeMatcher.resolveCards(
            episodes = episodes,
            fallbackDisplayNames = episodes.map { it.name },
            vodName = "测试番剧",
            flagName = "夸克",
            tmdbDetail = detail,
            tmdbEpisodesBySeason = mapOf(
                1 to listOf(TmdbEpisode(id = 28, seasonNumber = 1, episodeNumber = 28, name = "S1终章")),
                2 to listOf(TmdbEpisode(id = 29, seasonNumber = 2, episodeNumber = 1, name = "新旅程", stillPath = "/s2e1.jpg")),
            ),
            imageBase = "https://image.tmdb.org/t/p",
        )
        assertEquals("S1E28", loadedCards[0].badgeText)
        assertEquals("S1终章", loadedCards[0].title)
        assertNull(loadedCards[0].stillUrl)

        assertEquals("S2E1", loadedCards[1].badgeText)
        assertEquals("新旅程", loadedCards[1].title)
        assertEquals("https://image.tmdb.org/t/p/w300/s2e1.jpg", loadedCards[1].stillUrl)
    }

    @Test
    fun `specials S00E01 uses S0E1 badge and duplicate episode variants stay distinguishable`() {
        val detail = TmdbDetail(
            id = 6001,
            rawMediaType = "tv",
            name = "单季测试",
            numberOfSeasons = 1,
            seasons = listOf(
                TmdbSeasonSummary(id = 60, seasonNumber = 0, episodeCount = 1),
                TmdbSeasonSummary(id = 61, seasonNumber = 1, episodeCount = 10),
            ),
        )
        val episodes = listOf(
            Episode(name = "S00E01.mkv", url = "u0", index = 0),
            Episode(name = "第01集 4K.mkv", url = "u1a", index = 1),
            Episode(name = "第01集 1080P.mkv", url = "u1b", index = 2),
        )
        val cards = TmdbEpisodeMatcher.resolveCards(
            episodes = episodes,
            fallbackDisplayNames = episodes.map { it.name },
            vodName = "单季测试",
            flagName = "线路",
            tmdbDetail = detail,
            tmdbEpisodesBySeason = mapOf(
                1 to listOf(TmdbEpisode(id = 1, seasonNumber = 1, episodeNumber = 1, name = "序幕")),
            ),
            imageBase = "https://image.tmdb.org/t/p",
        )
        assertEquals("S0E1", cards[0].badgeText)
        assertEquals("序幕 · 4K", cards[1].title)
        assertEquals("序幕 · 1080P", cards[2].title)
    }

    @Test
    fun `all-extras list returns empty required seasons and non-mainline avoids Spider`() {
        assertFalse(TmdbEpisodeMatcher.isNonMainlineExtra("Spider-Man S01E01.mkv"))
        assertFalse(TmdbEpisodeMatcher.isNonMainlineExtra("Specials_Story_01.mp4"))
        assertTrue(TmdbEpisodeMatcher.isNonMainlineExtra("NCOP01.mkv"))
        assertTrue(TmdbEpisodeMatcher.isNonMainlineExtra("OVA02.mp4"))
        assertTrue(TmdbEpisodeMatcher.isNonMainlineExtra("SP1.mkv"))
        assertTrue(TmdbEpisodeMatcher.isNonMainlineExtra("花絮01.mp4"))

        val detail = TmdbDetail(
            id = 7001,
            rawMediaType = "tv",
            seasons = listOf(TmdbSeasonSummary(id = 70, seasonNumber = 1, episodeCount = 12)),
        )
        val allExtras = listOf(
            Episode(name = "PV01.mp4", url = "p1", index = 0),
            Episode(name = "NCED.mkv", url = "p2", index = 1),
        )
        assertTrue(
            TmdbEpisodeMatcher.resolveRequiredSeasons(detail, "番剧", "线路", allExtras).isEmpty()
        )
    }

    @Test
    fun `resolveRequiredSeasons caps count and resolveFallbackBackdropUrl selects best image`() {
        val manySeasons = (1..12).map { s ->
            TmdbSeasonSummary(id = s, seasonNumber = s, episodeCount = 10)
        }
        val detail = TmdbDetail(
            id = 4001,
            rawMediaType = "tv",
            seasons = manySeasons,
            images = TmdbImages(
                backdrops = listOf(
                    TmdbImageItem(filePath = "/zh_bd.jpg", iso639 = "zh", voteAverage = 9.0),
                    TmdbImageItem(filePath = "/clean_bd.jpg", iso639 = null, voteAverage = 7.0),
                ),
            ),
        )
        val episodes = (1..12).map { s -> Episode(name = "S${s}E01.mkv", url = "u$s", index = s - 1) }
        val required = TmdbEpisodeMatcher.resolveRequiredSeasons(detail, "长剧", "线路", episodes)
        assertEquals((1..8).toList(), required)

        val backdrop = TmdbEpisodeMatcher.resolveFallbackBackdropUrl(
            tmdbDetail = detail,
            tmdbImageBase = "https://image.tmdb.org/t/p",
            quickBackdrop = "https://example.com/quick.jpg",
            vodPic = "https://example.com/vod.jpg",
        )
        assertEquals("https://image.tmdb.org/t/p/w780/clean_bd.jpg", backdrop)
    }

    @Test
    fun `movie skips episode regex parsing and hides badge while keeping display name`() {
        val movieDetail = TmdbDetail(
            id = 8001,
            rawMediaType = "movie",
            title = "流浪地球2",
            backdropPath = "/wandering_earth_2.jpg",
        )
        val versions = listOf(
            Episode(name = "流浪地球2 4K HDR.mkv", url = "u1", index = 0),
            Episode(name = "流浪地球2 1080P.mp4", url = "u2", index = 1),
        )
        val displayNames = listOf("4K HDR", "1080P")
        val cards = TmdbEpisodeMatcher.resolveCards(
            episodes = versions,
            fallbackDisplayNames = displayNames,
            vodName = "流浪地球2",
            flagName = "夸克",
            tmdbDetail = movieDetail,
            tmdbEpisodesBySeason = emptyMap(),
            imageBase = "https://image.tmdb.org/t/p",
        )

        assertTrue(TmdbEpisodeMatcher.resolveRequiredSeasons(movieDetail, "流浪地球2", "夸克", versions).isEmpty())
        assertEquals(2, cards.size)
        cards.forEachIndexed { index, card ->
            assertNull(card.badgeText)
            assertNull(card.stillUrl)
            assertFalse(card.matchedTmdb)
            assertEquals(displayNames[index], card.title)
        }
        assertEquals(
            "https://image.tmdb.org/t/p/w780/wandering_earth_2.jpg",
            TmdbEpisodeMatcher.resolveFallbackBackdropUrl(movieDetail, "https://image.tmdb.org/t/p"),
        )
    }
}
