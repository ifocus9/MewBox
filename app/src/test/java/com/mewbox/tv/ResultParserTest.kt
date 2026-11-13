package com.mewbox.tv

import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultParserTest {

    private fun sample(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.bufferedReader(Charsets.UTF_8).readText()

    // ---------- 真实样本（M0 抓取） ----------

    @Test
    fun douban_home_hasClassesAndFilters() {
        val r = ResultParser.parseResult(sample("douban_home.json"))
        assertEquals(7, r.classes.size)
        assertEquals("hot_gaia", r.classes.first().typeId)
        assertEquals(7, r.filters.size)
        val hotFilters = r.filters["hot_gaia"]!!
        assertEquals(2, hotFilters.size)
        assertEquals("sort", hotFilters[0].key)
        assertEquals("排序", hotFilters[0].name)
        assertEquals(listOf("recommend", "time", "rank"), hotFilters[0].values.map { it.v })
        val movieFilters = r.filters["movie"]!!
        assertEquals(4, movieFilters.size)
        assertEquals("全部类型", movieFilters[0].values.first().n)
        assertEquals("", movieFilters[0].values.first().v)
    }

    @Test
    fun filters_supportsObjectAndInlineClassAndDirtyInput() {
        val json = """
            {
                "class": [
                    {
                        "type_id": "1",
                        "type_name": "电影",
                        "filters": [{"key": "year", "name": "年份", "init": "2026", "value": [{"n": "2026", "v": 2026}]}]
                    },
                    {"type_id": "2", "type_name": "剧集"}
                ],
                "filters": {
                    "2": {"key": "area", "name": "地区", "value": [{"n": "大陆", "v": "大陆"}]},
                    "empty": []
                }
            }
        """.trimIndent()
        val r = ResultParser.parseResult(json)
        assertEquals(1, r.filters["1"]?.size)
        assertEquals("2026", r.filters["1"]?.first()?.init)
        assertEquals("2026", r.filters["1"]?.first()?.values?.first()?.v)
        assertEquals("area", r.filters["2"]?.first()?.key)
        assertFalse(r.filters.containsKey("empty"))

        val dirty = ResultParser.parseResult("""{"filters": null, "class": []}""")
        assertTrue(dirty.filters.isEmpty())
    }

    @Test
    fun douban_category_isMsearch() {
        val r = ResultParser.parseResult(sample("douban_cat.json"))
        assertEquals(20, r.list.size)
        assertTrue(r.list.all { it.isMsearch })
        assertEquals(1, r.page)
        assertTrue(r.pageCount > 1)

        // 验证 msearch 包含匹配规则（对齐 FongMi 与 CatPawOpen 豆瓣源）
        assertTrue(Vod(vodId = "msearch:36179101").isMsearch)
        assertTrue(Vod(vodId = "msearch:").isMsearch)
        assertTrue(Vod(vodId = "msearch").isMsearch)
        assertTrue(Vod(vodId = "douban_msearch_123").isMsearch)
        assertFalse(Vod(vodId = "normal_12345").isMsearch)
    }

    @Test
    fun douban_detail_isEmpty() {
        val r = ResultParser.parseResult(sample("douban_detail.json"))
        assertTrue(r.list.isEmpty())
    }

    @Test
    fun jinpai_detail_hasFlagsAndEpisodes() {
        val vod = ResultParser.parseResult(sample("jinpai_detail.json")).list.single()
        assertTrue(vod.playFlags.isNotEmpty())
        val ep = vod.playFlags.first().episodes.first()
        assertEquals("146660/1314815", ep.url)
        assertEquals(0, ep.index)
    }

    @Test
    fun guazi_playId_isOpaque() {
        val vod = ResultParser.parseResult(sample("guazi_detail.json")).list.single()
        val ep = vod.playFlags.first().episodes.first()
        assertTrue(ep.url.contains("&"))
        assertTrue(ep.url.contains("||"))
        assertTrue(ep.url.startsWith("vod_d_id="))
    }

    @Test
    fun play_returnsLocalProxyUrl() {
        val r = ResultParser.parseResult(sample("jinpai_play.json"))
        assertEquals(0, r.parse)
        assertTrue(r.url.startsWith("http://127.0.0.1:9988/m3u8-proxy?"))
        assertFalse(r.needSniff)
    }

    @Test
    fun config_parsesSites() {
        val sites = ResultParser.parseSites(sample("config.json"))
        assertTrue(sites.isNotEmpty())
        val douban = sites.first { it.key == "t4_douban" }
        assertEquals(4, douban.type)
        assertTrue(douban.indexs)
        assertTrue(sites.all { it.api.isNotEmpty() })
    }

    // ---------- 脏数据容错 ----------

    @Test
    fun dirty_listAndClassAreNotArrays() {
        assertTrue(ResultParser.parseResult("""{"list":"","class":false}""").list.isEmpty())
        assertTrue(ResultParser.parseResult("""{"list":null,"class":""}""").classes.isEmpty())
    }

    @Test
    fun dirty_idsAcceptIntAndString() {
        val r = ResultParser.parseResult("""{"class":[{"type_id":1,"type_name":"a"},{"type_id":"x","type_name":"b"}],
            "list":[{"vod_id":123,"vod_name":"n"}]}""")
        assertEquals(listOf("1", "x"), r.classes.map { it.typeId })
        assertEquals("123", r.list.single().vodId)
    }

    @Test
    fun dirty_headerAsNestedJsonString() {
        val r = ResultParser.parseResult("""{"url":"u","header":"{\"Referer\":\"http://a\"}"}""")
        assertEquals("http://a", r.header["Referer"])
        val r2 = ResultParser.parseResult("""{"url":"u","header":{"User-Agent":"UA"}}""")
        assertEquals("UA", r2.header["User-Agent"])
    }

    @Test
    fun dirty_notJson_returnsEmptyResult() {
        val r = ResultParser.parseResult("<html>502</html>")
        assertTrue(r.list.isEmpty() && r.classes.isEmpty() && r.url.isEmpty())
    }

    @Test
    fun parse_flagFromBoolAndString() {
        assertEquals(1, ResultParser.parseResult("""{"parse":true}""").parse)
        assertEquals(1, ResultParser.parseResult("""{"parse":"1"}""").parse)
        assertTrue(ResultParser.parseResult("""{"jx":1}""").needSniff)
    }

    // ---------- 剧集拆分 ----------

    @Test
    fun flags_twoLevelSplit() {
        val flags = ResultParser.parseFlags("A\$\$\$B", "第1集\$u1#第2集\$u2\$\$\$正片\$u3")
        assertEquals(listOf("A", "B"), flags.map { it.flag })
        assertEquals(listOf("第1集", "第2集"), flags[0].episodes.map { it.name })
        assertEquals("u2", flags[0].episodes[1].url)
        assertEquals(1, flags[0].episodes[1].index)
        assertEquals("u3", flags[1].episodes.single().url)
    }

    @Test
    fun flags_noDollarMeansFeaturedName() {
        val ep = ResultParser.parseFlags("A", "onlyid").single().episodes.single()
        assertEquals("正片", ep.name)
        assertEquals("onlyid", ep.url)
    }

    @Test
    fun flags_mismatchedCountsAreSkipped() {
        assertEquals(1, ResultParser.parseFlags("A\$\$\$B", "x\$1").size)
        assertTrue(ResultParser.parseFlags("", "x\$1").isEmpty())
    }

    // ---------- 订阅合并 / 地址映射 ----------

    @Test
    fun merge_keepsUserToggles() {
        val incoming = listOf(
            Site(key = "a", name = "new", type = 4, api = "http://x", enabled = true, searchable = 1, changeable = true, quickSearch = true),
            Site(key = "b", name = "nosrch", type = 4, api = "http://z", enabled = true, searchable = 0, changeable = false, quickSearch = false)
        )
        val old = mapOf(
            "a" to Site(key = "a", name = "old", type = 4, api = "http://y", enabled = false, searchable = 2, isHome = true, changeable = true, quickSearch = true),
            "b" to Site(key = "b", name = "old_nosrch", type = 4, api = "http://z", enabled = true, searchable = 1, isHome = false, changeable = true, quickSearch = true)
        )
        val merged = VodConfig.merge(incoming, old)
        val ma = merged.first { it.key == "a" }
        assertEquals("new", ma.name)
        assertEquals("http://x", ma.api)
        assertFalse(ma.enabled)
        assertEquals(2, ma.searchable)
        assertFalse(ma.isSearchable)
        assertTrue(ma.isSearchSupport)
        assertTrue(ma.changeable)
        assertTrue(ma.quickSearch)
        // isHome 不由站点 merge 继承，由 Config.home 统一调度，因此 merge 结果中默认为 false
        assertFalse(ma.isHome)

        val mb = merged.first { it.key == "b" }
        assertEquals(0, mb.searchable)
        assertFalse(mb.isSearchable)
        assertFalse(mb.isSearchSupport)
        assertFalse(mb.changeable)
        assertFalse(mb.quickSearch)
    }

    @Test
    fun parseSites_basesetDefaultsToNonChangeableAndNonSearchable() {
        val json = """{"sites":[{"key":"nodejs_baseset","name":"配置[设]","type":3,"api":"node:/spider/baseset/3"}]}"""
        val sites = ResultParser.parseSites(json)
        val baseset = sites.single()
        assertFalse(baseset.changeable)
        assertFalse(baseset.quickSearch)
        assertEquals(0, baseset.searchable)
    }

    @Test
    fun hostMapping_rewritesLoopbackOnly() {
        val hm = HostMapping()
        val u = "http://127.0.0.1:9988/m3u8-proxy?url=x"
        assertEquals(u, hm.rewrite(u))
        hm.target = "10.0.2.2"
        assertEquals("http://10.0.2.2:9988/m3u8-proxy?url=x", hm.rewrite(u))
        hm.target = "192.168.1.5:8080"
        assertEquals("http://192.168.1.5:8080/m3u8-proxy?url=x", hm.rewrite(u))
        assertEquals("https://example.com/a", hm.rewrite("https://example.com/a"))
    }

    @Test
    fun play_returnsArrayUrls_forCloudDrive() {
        val json = """
            {
                "parse": 0,
                "url": [
                    "无限4k",
                    "https://dl-uf-zb.pds.uc.cn/stream.mkv?Expires=123",
                    "超清1080P",
                    "https://dl-uf-zb.pds.uc.cn/stream_1080p.mkv"
                ],
                "header": {
                    "User-Agent": "uc-cloud-drive",
                    "Referer": "https://dl-uf-zb.pds.uc.cn/"
                }
            }
        """.trimIndent()
        val r = ResultParser.parseResult(json)
        assertEquals(0, r.parse)
        assertEquals("https://dl-uf-zb.pds.uc.cn/stream.mkv?Expires=123", r.url)
        assertEquals(2, r.playUrls.size)
        assertEquals("无限4k", r.playUrls[0].name)
        assertEquals("https://dl-uf-zb.pds.uc.cn/stream.mkv?Expires=123", r.playUrls[0].url)
        assertEquals("超清1080P", r.playUrls[1].name)
        assertEquals("https://dl-uf-zb.pds.uc.cn/stream_1080p.mkv", r.playUrls[1].url)
        assertEquals("uc-cloud-drive", r.header["User-Agent"])
    }

    @Test
    fun inferMimeType_detectsM3u8ProxyAndHlsStreams() {
        val userUrl = "http://127.0.0.1:9988/m3u8-proxy?url=https%3A%2F%2Fvd.wmvbo.com%2Fea9c768a223550c9362bf59d47275a0c%2F20261029175803%2F5738039402bc7cbc3fb811d2%2Fdecry%2Fvd%2F20260814%2FOGI2ZmY3NGJl%2F122608%2F2758_1080%2Faac%2Fh264%2Fhls%2Fdecrypt%2Findex.m3u8&h=eyJVc2VyLUFnZW50IjoiTGF2Zi81Ny44My4xMDAiLCJSZWZlcmVyIjoiaHR0cDovL1dKaVp4TFhBMi5jb20vIiwiQWNjZXB0LUVuY29kaW5nIjoiZ3ppcCJ9"
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, com.mewbox.tv.player.Players.inferMimeType(userUrl))

        val directM3u8 = "https://example.com/live/stream.m3u8?token=123"
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, com.mewbox.tv.player.Players.inferMimeType(directM3u8))

        val dashUrl = "https://example.com/manifest.mpd"
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_MPD, com.mewbox.tv.player.Players.inferMimeType(dashUrl))

        val untypedWithFormat = "http://example.com/live/stream"
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, com.mewbox.tv.player.Players.inferMimeType(untypedWithFormat, format = "hls"))
    }
}
