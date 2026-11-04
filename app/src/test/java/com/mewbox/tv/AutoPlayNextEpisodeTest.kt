package com.mewbox.tv

import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.ui.detail.DetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlayNextEpisodeTest {

    private val testEpisodes = listOf(
        Episode(name = "第01集", url = "url_1", index = 0),
        Episode(name = "第02集", url = "url_2", index = 1),
        Episode(name = "第03集", url = "url_3", index = 2),
    )
    private val testFlag = Flag(flag = "默认线路", episodes = testEpisodes)

    @Test
    fun normalSort_middleEpisode_hasBothPrevAndNext() {
        val (hasPrev, hasNext) = DetailViewModel.checkHasNavEpisodes(
            flag = testFlag,
            currentEpisode = testEpisodes[1],
            isReversed = false,
        )
        assertTrue(hasPrev)
        assertTrue(hasNext)

        val nextEp = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[1],
            isReversed = false,
            offset = 1,
        )
        assertEquals("第03集", nextEp?.name)

        val prevEp = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[1],
            isReversed = false,
            offset = -1,
        )
        assertEquals("第01集", prevEp?.name)
    }

    @Test
    fun normalSort_firstEpisode_hasNoPrev() {
        val (hasPrev, hasNext) = DetailViewModel.checkHasNavEpisodes(
            flag = testFlag,
            currentEpisode = testEpisodes[0],
            isReversed = false,
        )
        assertFalse(hasPrev)
        assertTrue(hasNext)

        val prevEp = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[0],
            isReversed = false,
            offset = -1,
        )
        assertNull(prevEp)
    }

    @Test
    fun normalSort_lastEpisode_hasNoNext() {
        val (hasPrev, hasNext) = DetailViewModel.checkHasNavEpisodes(
            flag = testFlag,
            currentEpisode = testEpisodes[2],
            isReversed = false,
        )
        assertTrue(hasPrev)
        assertFalse(hasNext)

        val nextEp = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[2],
            isReversed = false,
            offset = 1,
        )
        assertNull(nextEp)
    }

    @Test
    fun reversedSort_navigatesProperly() {
        // 倒序状态下：列表展示为 [第03集, 第02集, 第01集]
        // 第03集在顶部：无上一集，下一集为第02集
        val (hasPrevTop, hasNextTop) = DetailViewModel.checkHasNavEpisodes(
            flag = testFlag,
            currentEpisode = testEpisodes[2],
            isReversed = true,
        )
        assertFalse(hasPrevTop)
        assertTrue(hasNextTop)

        val nextFromTop = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[2],
            isReversed = true,
            offset = 1,
        )
        assertEquals("第02集", nextFromTop?.name)

        // 第01集在底部：上一集为第02集，无下一集
        val (hasPrevBottom, hasNextBottom) = DetailViewModel.checkHasNavEpisodes(
            flag = testFlag,
            currentEpisode = testEpisodes[0],
            isReversed = true,
        )
        assertTrue(hasPrevBottom)
        assertFalse(hasNextBottom)

        val nextFromBottom = DetailViewModel.calculateNavEpisode(
            flag = testFlag,
            currentEpisode = testEpisodes[0],
            isReversed = true,
            offset = 1,
        )
        assertNull(nextFromBottom)
    }

    @Test
    fun autoPlayToast_formatting() {
        val nextEp = testEpisodes[1]
        val toastText = "准备播放：${nextEp.name}"
        assertEquals("准备播放：第02集", toastText)

        val lastToastText = "已经是最后一集了"
        assertEquals("已经是最后一集了", lastToastText)
    }
}

