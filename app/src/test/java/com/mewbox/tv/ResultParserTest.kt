package com.mewbox.tv

import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.bean.Site
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
    }

    @Test
    fun douban_category_isMsearch() {
        val r = ResultParser.parseResult(sample("douban_cat.json"))
        assertEquals(20, r.list.size)
        assertTrue(r.list.all { it.isMsearch })
        assertEquals(1, r.page)
        assertTrue(r.pageCount > 1)
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
        val incoming = listOf(Site(key = "a", name = "new", type = 4, api = "http://x", enabled = true, searchable = true))
        val old = mapOf("a" to Site(key = "a", name = "old", type = 4, api = "http://y", enabled = false, searchable = false, isHome = true))
        val m = VodConfig.merge(incoming, old).single()
        assertEquals("new", m.name)
        assertEquals("http://x", m.api)
        assertFalse(m.enabled)
        assertFalse(m.searchable)
        assertTrue(m.isHome)
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
}
