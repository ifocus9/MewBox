package com.mewbox.tv

import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import org.junit.Assert.assertEquals
import org.junit.Test

class SiteSearchOptimizationTest {

    @Test
    fun siteSearch_prioritizesFastAndQuickSearchSites() {
        val sites = listOf(
            Site(key = "s1", name = "豆瓣[荐]", type = 4, api = "", priority = 0, quickSearch = false),
            Site(key = "s2", name = "玩偶[盘]", type = 4, api = "", priority = 1, quickSearch = false),
            Site(key = "s3", name = "多多[盘]", type = 4, api = "", priority = 2, quickSearch = false),
            Site(key = "s4", name = "厂长[秒]", type = 4, api = "", priority = 3, quickSearch = false),
            Site(key = "s5", name = "常规聚合", type = 4, api = "", priority = 4, quickSearch = true),
            Site(key = "s6", name = "金牌[秒]", type = 4, api = "", priority = 5, quickSearch = true),
        )

        val sorted = sites.sortedWith(
            compareByDescending<Site> { it.name.contains("秒") }
                .thenByDescending { it.quickSearch }
                .thenBy { it.priority }
        )

        // "[秒]" 站点优先，同为秒播的根据 quickSearch / priority 排序
        assertEquals("s6", sorted[0].key) // 金牌[秒] (秒 + quickSearch)
        assertEquals("s4", sorted[1].key) // 厂长[秒] (秒)
        assertEquals("s5", sorted[2].key) // 常规聚合 (quickSearch)
        assertEquals("s1", sorted[3].key) // 豆瓣 (priority 0)
        assertEquals("s2", sorted[4].key) // 玩偶 (priority 1)
        assertEquals("s3", sorted[5].key) // 多多 (priority 2)
    }

    @Test
    fun searchDeduplication_seenKeysFiltersDuplicates() {
        val seenKeys = HashSet<String>()
        val batch1 = listOf(
            Vod(siteKey = "czs", vodId = "101", vodName = "庆余年 第一季"),
            Vod(siteKey = "czs", vodId = "102", vodName = "庆余年 第二季"),
        )
        val batch2 = listOf(
            Vod(siteKey = "czs", vodId = "101", vodName = "庆余年 第一季"), // 重复
            Vod(siteKey = "jp", vodId = "201", vodName = "庆余年 第二季"), // 不同站点，保留
        )

        fun processBatch(batch: List<Vod>): List<Vod> {
            val uniqueVods = ArrayList<Vod>(batch.size)
            for (vod in batch) {
                val key = "${vod.siteKey}_${vod.vodId}"
                if (seenKeys.add(key)) {
                    uniqueVods.add(vod)
                }
            }
            return uniqueVods
        }

        val res1 = processBatch(batch1)
        val res2 = processBatch(batch2)

        assertEquals(2, res1.size)
        assertEquals(1, res2.size)
        assertEquals("jp", res2[0].siteKey)
        assertEquals("201", res2[0].vodId)
    }
}
