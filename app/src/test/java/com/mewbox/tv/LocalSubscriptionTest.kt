package com.mewbox.tv

import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.api.parser.ResultParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalSubscriptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isLocalFile_detectsCorrectSchemes() {
        assertTrue(VodConfig.isLocalFile("file:///storage/emulated/0/Download/tvbox.json"))
        assertTrue(VodConfig.isLocalFile("FILE:///sdcard/config.json"))
        assertTrue(VodConfig.isLocalFile("content://com.android.providers.media.documents/document/123"))
        assertTrue(VodConfig.isLocalFile("/storage/emulated/0/config.json"))

        assertFalse(VodConfig.isLocalFile("http://192.168.1.100:8080/tvbox.json"))
        assertFalse(VodConfig.isLocalFile("https://raw.githubusercontent.com/test/tvbox.json"))
        assertFalse(VodConfig.isLocalFile("""{"sites":[{"key":"demo","name":"Demo"}]}"""))
    }

    @Test
    fun localConfigFile_canBeParsedCorrectly() {
        val sampleJson = """
            {
                "sites": [
                    {
                        "key": "local_site_1",
                        "name": "本地测试源1",
                        "type": 3,
                        "api": "csp_Local",
                        "searchable": 1
                    },
                    {
                        "key": "local_site_2",
                        "name": "本地测试源2",
                        "type": 0,
                        "api": "https://api.example.com/vod",
                        "searchable": 0
                    }
                ]
            }
        """.trimIndent()

        val file = tempFolder.newFile("test_config.json")
        file.writeText(sampleJson, Charsets.UTF_8)

        assertTrue(file.exists())
        val content = file.readText(Charsets.UTF_8)
        val sites = ResultParser.parseSites(content)

        assertEquals(2, sites.size)
        assertEquals("local_site_1", sites[0].key)
        assertEquals("本地测试源1", sites[0].name)
        assertEquals(3, sites[0].type)
        assertEquals(1, sites[0].searchable)

        assertEquals("local_site_2", sites[1].key)
        assertEquals("本地测试源2", sites[1].name)
        assertEquals(0, sites[1].type)
        assertEquals(0, sites[1].searchable)
    }

    @Test
    fun localHtmlFile_isDetectedCorrectly() {
        assertTrue(VodConfig.isHtmlFile("file:///storage/emulated/0/Download/index.html"))
        assertTrue(VodConfig.isHtmlFile("file:///storage/emulated/0/Download/my_site.htm"))
        assertTrue(VodConfig.isHtmlFile("/storage/emulated/0/page.html?v=123#test"))
        assertFalse(VodConfig.isHtmlFile("file:///storage/emulated/0/Download/config.json"))

        val htmlContent = "<!DOCTYPE html><html><head><title>本地测试网页源</title></head><body><h1>Hello</h1></body></html>"
        val htmlFile = tempFolder.newFile("index.html")
        htmlFile.writeText(htmlContent, Charsets.UTF_8)

        assertTrue(VodConfig.isHtmlResponse("text/html", htmlContent))
        assertEquals("本地测试网页源", VodConfig.extractHtmlTitle(htmlContent))
    }
}

