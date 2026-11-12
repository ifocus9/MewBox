package com.mewbox.tv

import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.net.DriveCheckService
import com.mewbox.tv.api.parser.toJsonObjectOrEmpty
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.mewbox.tv.domain.WebHomeInlineStore
import com.mewbox.tv.ui.web.fm.FmJsSdk
import com.mewbox.tv.ui.web.fm.HomeWebBridge
import com.mewbox.tv.ui.web.fm.WebHomeViewport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSourceTest {

    @Test
    fun testIsHtmlResponseDetection() {
        val doctypeHtml = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"><title>Apple Music</title></head>
            <body><div id="app"></div></body>
            </html>
        """.trimIndent()
        assertTrue(VodConfig.isHtmlResponse("text/html; charset=utf-8", doctypeHtml))
        assertTrue(VodConfig.isHtmlResponse("text/plain", doctypeHtml))

        val bomHtml = "\uFEFF  \n<html><head><title>测试网页</title></head><body></body></html>"
        assertTrue(VodConfig.isHtmlResponse("", bomHtml))

        val jsonBody = """{"sites":[{"key":"csp_test","name":"测试","type":4,"api":"http://127.0.0.1/vod"}]}"""
        assertFalse(VodConfig.isHtmlResponse("application/json", jsonBody))
        // 即使服务端错误返回 text/html，JSON 配置也不应误判为 HTML 网页
        assertFalse(VodConfig.isHtmlResponse("text/html", jsonBody))
        assertFalse(VodConfig.isHtmlResponse("text/html", ""))
    }

    @Test
    fun testExtractHtmlTitle() {
        val html = """
            <!doctype html>
            <html>
            <head>
                <title>
                    Apple &amp; Music &nbsp; 网页版
                </title>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        assertEquals("Apple & Music 网页版", VodConfig.extractHtmlTitle(html))
        assertEquals("", VodConfig.extractHtmlTitle("<html><body>无标题</body></html>"))
    }

    @Test
    fun testConfigEntityHtmlFlag() {
        val vodConfig = Config(url = "https://example.com/config.json", name = "点播源", type = Config.TYPE_VOD)
        val htmlConfig = Config(url = "https://example.com/index.html", name = "Apple Music", type = Config.TYPE_HTML)
        assertFalse(vodConfig.isHtml)
        assertTrue(htmlConfig.isHtml)
        assertEquals("Apple Music", htmlConfig.desc)
    }

    @Test
    fun testWebHomeInlineStorePlayUrlAndVodInline() = runBlocking {
        val store = WebHomeInlineStore()

        val playUrlParams = """
            {
                "url": "https://cdn.example.com/live/stream.m3u8",
                "title": "测试直链视频",
                "pic": "https://cdn.example.com/poster.jpg",
                "headers": {
                    "Referer": "https://example.com/",
                    "User-Agent": "MewBoxTest/1.0"
                },
                "position": 15000
            }
        """.trimIndent().toJsonObjectOrEmpty()

        val entry = store.registerPlayUrl(playUrlParams)
        assertEquals("测试直链视频", entry.vod.vodName)
        assertEquals(WebHomeInlineStore.KEY, entry.vod.siteKey)
        assertEquals(1, entry.vod.playFlags.size)
        assertEquals(1, entry.vod.playFlags[0].episodes.size)

        val initialHistory = store.consumeInitialHistory(entry.vod.vodId)
        assertNotNull(initialHistory)
        assertEquals(15000L, initialHistory?.position)

        val epId = entry.vod.playFlags[0].episodes[0].url
        val resolved = store.resolvePlay(entry.vod.playFlags[0].flag, epId)
        assertEquals("https://cdn.example.com/live/stream.m3u8", resolved.url)
        assertEquals("https://example.com/", resolved.header["Referer"])
        assertEquals("MewBoxTest/1.0", resolved.header["User-Agent"])

        // 测试带动态 JS Resolver 的多线路剧集内联点播
        store.jsResolver = WebHomeInlineStore.InlineJsResolver { _, _, episodeId, _ ->
            buildJsonObject {
                put("url", "https://media.example.com/resolved/$episodeId.mp4")
                put("headers", buildJsonObject { put("Origin", "https://media.example.com") })
            }
        }
        val inlineVodParams = """
            {
                "vodName": "内联剧集测试",
                "hasResolver": true,
                "flagIndex": 0,
                "episodeIndex": 1,
                "flags": [
                    {
                        "flag": "蓝光线路",
                        "episodes": [
                            {"name": "第1集", "id": "ep_1"},
                            {"name": "第2集", "id": "ep_2"}
                        ]
                    }
                ]
            }
        """.trimIndent().toJsonObjectOrEmpty()

        val vodEntry = store.registerPlayVodInline(inlineVodParams)
        assertEquals("内联剧集测试", vodEntry.vod.vodName)
        assertEquals(2, vodEntry.vod.playFlags[0].episodes.size)
        assertEquals(1, vodEntry.initialEpisodeIndex)
        assertEquals("第2集", vodEntry.initialEpisodeName)

        val ep2Key = vodEntry.vod.playFlags[0].episodes[1].url
        val resolvedEp2 = store.resolvePlay("蓝光线路", ep2Key)
        assertEquals("https://media.example.com/resolved/ep_2.mp4", resolvedEp2.url)
        assertEquals("https://media.example.com", resolvedEp2.header["Origin"])
    }

    @Test
    fun testFmJsSdkContainsFullApiSurface() {
        val viewport = WebHomeViewport(safeTopPx = 24, safeBottomPx = 16)
        val script = FmJsSdk.buildScript(viewport)
        assertTrue(script.contains("window.fongmi = fongmi"))
        assertTrue(script.contains("window.fm = fm"))
        assertTrue(script.contains("fm-native"))
        assertTrue(script.contains("fmsdk"))
        assertTrue(script.contains("net.request"))
        assertTrue(script.contains("net.resourceUrl"))
        assertTrue(script.contains("player.playUrl"))
        assertTrue(script.contains("player.playVodInline"))
        assertTrue(script.contains("pan.play"))
        assertTrue(script.contains("pan.check"))
        assertTrue(script.contains("fongmiready"))
        assertTrue(script.contains("fmready"))
        assertTrue(script.contains("--fm-safe-top"))
    }

    @Test
    fun testPanShareUrlAndPushSiteDetection() {
        assertTrue(DriveCheckService.isPanShareUrl("https://pan.quark.cn/s/abc123456"))
        assertTrue(DriveCheckService.isPanShareUrl("https://drive.uc.cn/s/xyz987"))
        assertTrue(DriveCheckService.isPanShareUrl("https://www.alipan.com/s/def456"))
        assertTrue(DriveCheckService.isPanShareUrl("https://pan.baidu.com/s/1abc?pwd=1234"))
        assertTrue(DriveCheckService.isPanShareUrl("https://cloud.189.cn/t/uvwx"))
        assertTrue(DriveCheckService.isPanShareUrl("https://www.123pan.com/s/abcd-efgh"))
        assertTrue(DriveCheckService.isPanShareUrl("push://https://pan.quark.cn/s/abc123456"))
        assertFalse(DriveCheckService.isPanShareUrl("https://cdn.example.com/video/index.m3u8"))

        assertEquals("quark", DriveCheckService.inferDiskType("https://pan.quark.cn/s/abc123456"))
        assertEquals("uc", DriveCheckService.inferDiskType("https://drive.uc.cn/s/xyz987"))
        assertEquals("aliyun", DriveCheckService.inferDiskType("https://www.alipan.com/s/def456"))
        assertEquals("https://pan.quark.cn/s/abc123456", HomeWebBridge.stripPush("push://https://pan.quark.cn/s/abc123456"))

        assertTrue(VodConfig.isPushSite(Site(key = "push_agent", name = "推送", type = 3, api = "node:/spider/push_agent/3")))
        assertTrue(VodConfig.isPushSite(Site(key = "nodejs_push_agent", name = "推送", type = 3, api = "node:/spider/push_agent/3")))
        assertFalse(VodConfig.isPushSite(Site(key = "douban", name = "豆瓣", type = 3, api = "node:/spider/douban/3")))
    }
}
