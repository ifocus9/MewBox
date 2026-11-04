package com.mewbox.tv

import com.fongmi.nodejs.Crypto
import com.fongmi.nodejs.NodeBundle
import com.fongmi.nodejs.NodeConfigMapper
import com.fongmi.nodejs.NodeRoute
import com.mewbox.tv.engine.nodejs.NodeSpider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeEngineTest {

    @Test
    fun testNodeRoute() {
        val api = "node:/spider/bilibili/3"
        assertEquals("/spider/bilibili/3", NodeRoute.path(api))
        assertEquals("node:/spider/bilibili/3/home", NodeRoute.append(api, "/home"))
        assertEquals("node:/spider/bilibili/3/detail", NodeRoute.append(api, "detail"))

        val apiWithQuery = "node:/spider/alists/3?token=123"
        assertEquals("node:/spider/alists/3/play?token=123", NodeRoute.append(apiWithQuery, "/play"))
    }

    @Test
    fun testNodeConfigMapper() {
        val rawNodeConfig = """
            {
                "data": {
                    "video": {
                        "sites": [
                            {
                                "key": "nodejs_douban",
                                "name": "豆瓣",
                                "type": 3,
                                "api": "/spider/douban/3",
                                "enable": 1
                            },
                            {
                                "key": "nodejs_disabled",
                                "name": "已禁用",
                                "type": 3,
                                "enable": 0
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val transformed = NodeConfigMapper.transform(rawNodeConfig)
        assertTrue(transformed.contains(""""api":"node:/spider/douban/3""""))
        assertTrue(transformed.contains(""""type":3"""))
        // enable: 0 应该被过滤掉
        assertFalse(transformed.contains("nodejs_disabled"))
    }

    @Test
    fun testNodeBundleIsConfig() {
        assertTrue(NodeBundle.isConfig("https://example.com/spider/index.js.md5"))
        assertTrue(NodeBundle.isConfig("http://127.0.0.1:8080/index.js.md5?v=1"))
        assertTrue(NodeBundle.isConfig("index.js.md5"))
        assertFalse(NodeBundle.isConfig("https://example.com/spider/config.json"))
        assertFalse(NodeBundle.isConfig("content://media/external/file/123"))
    }

    @Test
    fun testNodeBundleParseMd5() {
        val md5Text = "  e10adc3949ba59abbe56e057f20f883e  \n"
        assertEquals("e10adc3949ba59abbe56e057f20f883e", NodeBundle.parseMd5(md5Text))
    }

    @Test
    fun testNodeSpiderAddMissingActions() {
        val json = """
            {
                "list": [
                    { "vod_id": "123", "vod_name": "测试电影" }
                ]
            }
        """.trimIndent()

        val processed = NodeSpider.addMissingActions(json)
        assertTrue(processed.contains(""""action":"123""""))
    }

    @Test
    fun testCompanion() {
        val source = "http://cat.900730.xyz/nodejs/dist/index.js.md5"
        assertEquals("http://cat.900730.xyz/nodejs/dist/index.js", NodeBundle.companion(source, "index.js"))
        assertEquals("http://cat.900730.xyz/nodejs/dist/index.config.js", NodeBundle.companion(source, "index.config.js"))
    }

    @Test
    fun testCrypto() {
        val md5 = Crypto.md5("hello")
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5)

        val sha256 = Crypto.sha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256)
    }
}

