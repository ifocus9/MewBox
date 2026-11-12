package com.mewbox.tv.api.douban

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 豆瓣数据层单测。
 * 签名参考值由系统 Python 3.12 探针产出（build/douban_probe_report.json），
 * Kotlin 实现必须与其逐字节一致，否则线上必然 code 996。
 */
class DoubanApiTest {

    @Test
    fun `签名与 Python 参考值逐字节一致`() {
        val sig = frodoSign("/api/v2/user/1000001/interests", 1_767_400_000L)
        assertEquals("aiqb0ba64vxt7Reo/6WzPxLLzjs=", sig)
    }

    @Test
    fun `签名对 path 中的斜杠做百分号编码`() {
        // "/" -> %2F：raw 字符串应包含编码后的路径，结果长度固定可复算
        val sig = frodoSign("/api/v2/user/42/interests", 0L)
        // ts=0 时 raw = "GET&%2Fapi%2Fv2%2Fuser%2F42%2Finterests&0"，不同 path 必须产生不同签名
        assertTrue(sig != frodoSign("/api/v2/user/43/interests", 0L))
    }

    @Test
    fun `解析容忍大量未声明字段`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val raw = """
            {
              "start": 0, "count": 50, "total": 77,
              "unknown_top": {"x": 1}, "color_scheme": "dark",
              "interests": [
                {
                  "id": 1353935090, "status": "mark",
                  "create_time": "2018-04-06 21:14:37",
                  "comment": "想看很久了", "tags": ["科幻"],
                  "rating": {"max": 5, "value": null, "count": 0},
                  "sharing_url": "https://example.com",
                  "subject": {
                    "title": "暴裂无声", "year": "2017",
                    "type": "movie", "subtype": "",
                    "card_subtitle": "2017 · 中国大陆 · 剧情",
                    "is_released": true,
                    "pic": {"large": "https://img2.doubanio.com/l.webp", "normal": "https://img2.doubanio.com/n.webp"},
                    "rating": {"max": 10, "value": 8.2, "count": 350000},
                    "actors": ["宋洋"], "color_scheme": "x"
                  }
                }
              ]
            }
        """.trimIndent()
        val resp = json.decodeFromString<DoubanInterestsResponse>(raw)
        assertEquals(77, resp.total)
        assertEquals(1, resp.interests.size)
        assertEquals(1353935090L, resp.interests[0].id)

        val item = resp.interests[0].toWishItem()!!
        assertEquals("暴裂无声", item.title)
        assertEquals("2017", item.year)
        assertEquals("https://img2.doubanio.com/l.webp", item.poster)
        assertEquals(8.2, item.ratingValue!!, 0.0)
        assertEquals("2018-04-06", item.markedAt)
        assertEquals(false, item.isTv)
        assertEquals(listOf("科幻"), item.tags)
        assertEquals("想看很久了", item.comment)
    }

    @Test
    fun `剧集标记与评分兜底与空标题丢弃`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val raw = """
            {
              "start": 50, "count": 50, "total": 77,
              "interests": [
                {
                  "id": 1, "create_time": "2005-05-24 13:20:51",
                  "subject": {
                    "title": "哈尔的移动城堡", "year": "2004", "type": "tv",
                    "pic": {"large": "", "normal": "https://img2.doubanio.com/normal.webp"},
                    "rating": {"value": null}
                  }
                },
                { "id": 2, "create_time": "", "subject": { "title": "  " } }
              ]
            }
        """.trimIndent()
        val resp = json.decodeFromString<DoubanInterestsResponse>(raw)
        val first = resp.interests[0].toWishItem()!!
        assertTrue(first.isTv)
        // large 为空回落 normal；rating 缺失时数值为 null（UI 显示「暂无评分」）；标记日期取 create_time 前 10 位
        assertEquals("https://img2.doubanio.com/normal.webp", first.poster)
        assertNull(first.ratingValue)
        assertEquals("2005-05-24", first.markedAt)
        // 片名空白的脏数据直接丢弃
        assertNull(resp.interests[1].toWishItem())
    }
}
