package com.mewbox.tv.ui.detail

import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod

data class DetailUiState(
    val isLoading: Boolean = true,
    val vod: Vod? = null,
    val currentSite: Site? = null,
    val selectedFlag: Flag? = null,
    val selectedEpisode: Episode? = null,
    val playUrls: List<PlayUrl> = emptyList(),
    val selectedPlayUrl: PlayUrl? = null,
    val isResolvingPlay: Boolean = false,
    val isMsearch: Boolean = false,
    val isAutoSearching: Boolean = false,
    val autoSearchMessage: String? = null,
    val changeSources: List<Vod> = emptyList(),
    val isSearchingChangeSources: Boolean = false,
    val isEpisodeReversed: Boolean = false,
    /** 选集标题精简（对齐 MewPlayer 的「精简」开关），全局记忆 */
    val isCompactEpisodeTitle: Boolean = false,
    val hasPreviousEpisode: Boolean = false,
    val hasNextEpisode: Boolean = false,
    val error: String? = null,
    val toastMessage: String? = null,
    val tmdbDetail: TmdbDetail? = null,
    val tmdbImageBase: String = "",
    /** searchMulti 命中后第一时间回填，供海报与全屏虚化背景快速出图 */
    val quickPoster: String? = null,
    val quickBackdrop: String? = null,
    val resumeEpisodeText: String? = null,
    val startInFullscreen: Boolean = false,
    val showTmdbMatchDialog: Boolean = false,
    val isSearchingTmdb: Boolean = false,
    val tmdbSearchKeyword: String = "",
    val tmdbSearchResults: List<TmdbMedia> = emptyList(),
    val opening: Long = 0L,
    val ending: Long = 0L,
    val closeScreen: Boolean = false,
    val tmdbSearchError: String? = null,
    val isTmdbConfigured: Boolean = true,
)

