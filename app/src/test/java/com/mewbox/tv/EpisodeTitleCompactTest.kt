package com.mewbox.tv

import com.mewbox.tv.bean.Episode
import com.mewbox.tv.util.EpisodeTitleCompact
import org.junit.Test

/**
 * 精简标题的行为快照：跑一遍典型命名，确认「精简」开关能真的把集数提出来。
 * 只做展示性输出，不做严格断言，避免不同片源命名差异导致测试脆弱。
 */
class EpisodeTitleCompactTest {

    private fun names(vararg values: String): List<Episode> =
        values.mapIndexed { index, value -> Episode(name = value, url = "url_$index", index = index) }

    private fun print(case: String, values: List<String>) {
        val compacted = EpisodeTitleCompact.displayNames(names(*values.toTypedArray()), compact = true)
        println("== $case ==")
        values.forEachIndexed { index, raw -> println("  ${raw}\n    -> ${compacted[index]}") }
    }

    @Test
    fun compact_typicalSources() {
        print("T4 常规集数", listOf("第01集", "第02集", "第03集"))
        print("文件式 4K", listOf(
            "遮天.S01E01.2160P.HDR.WEB-DL.mp4",
            "遮天.S01E02.2160P.HDR.WEB-DL.mp4",
            "遮天.S01E03.2160P.HDR.WEB-DL.mp4",
        ))
        print("网盘带组名", listOf(
            "[DBD-Raws][遮天][01][1080P][HEVC_AAC].mkv",
            "[DBD-Raws][遮天][02][1080P][HEVC_AAC].mkv",
            "[DBD-Raws][遮天][03][1080P][HEVC_AAC].mkv",
        ))
        print("集数+画质混排", listOf("01-4K 国语", "02-4K 国语", "03-4K 国语"))
        print("序号+剧情标题", listOf("【1】初入江湖", "【2】重逢", "【3】决战"))
        print("同集多版本", listOf("第01集 4K", "第01集 1080P", "第02集 4K"))
    }
}
