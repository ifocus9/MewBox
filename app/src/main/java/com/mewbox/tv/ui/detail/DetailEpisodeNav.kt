package com.mewbox.tv.ui.detail

import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag

/**
 * 选集导航与正倒序纯函数计算
 */
object DetailEpisodeNav {

    fun calculateNavEpisode(
        flag: Flag,
        currentEpisode: Episode,
        isReversed: Boolean,
        offset: Int,
    ): Episode? {
        val index = flag.episodes.indexOf(currentEpisode)
        if (index < 0) return null
        val targetIndex = if (isReversed) index - offset else index + offset
        return flag.episodes.getOrNull(targetIndex)
    }

    fun checkHasNavEpisodes(
        flag: Flag,
        currentEpisode: Episode,
        isReversed: Boolean,
    ): Pair<Boolean, Boolean> {
        val index = flag.episodes.indexOf(currentEpisode)
        if (index < 0) return false to false
        val hasPrev = if (isReversed) index < flag.episodes.size - 1 else index > 0
        val hasNext = if (isReversed) index > 0 else index < flag.episodes.size - 1
        return hasPrev to hasNext
    }
}

