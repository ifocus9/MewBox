package com.mewbox.tv.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.Players
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: SiteRepository,
    private val playbackTracker: DetailPlaybackTracker,
    private val tmdbCoordinator: DetailTmdbCoordinator,
    private val sourceCoordinator: DetailSourceCoordinator,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val players: Players get() = playbackTracker.players

    private var currentSiteKey: String = savedStateHandle["siteKey"] ?: ""
    private var currentVodId: String = savedStateHandle["vodId"] ?: ""
    private val initialVodName: String = savedStateHandle["vodName"] ?: ""
    val initialVodPic: String = savedStateHandle["vodPic"] ?: ""
    val autoPlay: Boolean = savedStateHandle["autoPlay"] ?: false

    private val _uiState = MutableStateFlow(
        DetailUiState(
            isLoading = true,
            isResolvingPlay = autoPlay,
            isMsearch = currentVodId.contains("msearch", ignoreCase = true),
            vod = if (initialVodName.isNotEmpty() || initialVodPic.isNotEmpty()) {
                Vod(vodId = currentVodId, vodName = initialVodName, vodPic = initialVodPic)
            } else null,
            startInFullscreen = false,
            isCompactEpisodeTitle = settingsRepository.compactEpisodeTitle,
        )
    )
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var playJob: Job? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var lastFormat: String = ""

    init {
        observePlayerState()
        observeCompactPreference()
        loadDetail(currentSiteKey, currentVodId, initialVodName, autoPlay)
    }

    private fun observeCompactPreference() {
        viewModelScope.launch {
            settingsRepository.compactEpisodeTitleFlow.collect { compact ->
                _uiState.update { if (it.isCompactEpisodeTitle == compact) it else it.copy(isCompactEpisodeTitle = compact) }
            }
        }
    }

    fun toggleEpisodeCompact() {
        val next = !_uiState.value.isCompactEpisodeTitle
        _uiState.update { it.copy(isCompactEpisodeTitle = next) }
        viewModelScope.launch { settingsRepository.setCompactEpisodeTitle(next) }
    }

    private fun observePlayerState() {
        playbackTracker.observePlayerState(
            scope = viewModelScope,
            getOpening = { _uiState.value.opening },
            getEnding = { _uiState.value.ending },
            onPlaybackStarted = { resumeEp ->
                _uiState.update {
                    it.copy(
                        resumeEpisodeText = resumeEp,
                    )
                }
            },
            onAutoPlayNext = {
                playNextEpisode(isAutoPlay = true)
            },
        )
    }

    fun retry() {
        loadDetail(currentSiteKey, currentVodId, initialVodName, autoPlay)
    }

    fun loadDetail(
        siteKey: String,
        vodId: String,
        fallbackVodName: String = "",
        shouldAutoPlay: Boolean = autoPlay,
    ) {
        currentSiteKey = siteKey
        currentVodId = vodId

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    isResolvingPlay = shouldAutoPlay,
                    isMsearch = vodId.contains("msearch", ignoreCase = true),
                )
            }

            val site = repository.getSite(siteKey)
            if (site == null) {
                _uiState.update { it.copy(isLoading = false, isResolvingPlay = false, error = "站点不存在或已被禁用") }
                return@launch
            }

            _uiState.update { it.copy(currentSite = site) }

            // 对齐 FongMi：若条目为配置/网页动作类条目，直接触发站点 action 并退出
            if (Vod.isActionId(vodId)) {
                Timber.i("检测到 vodId 为配置动作，触发 action 并跳过详情页: siteKey=$siteKey, vodId=$vodId")
                repository.action(site, vodId)
                _uiState.update { it.copy(isLoading = false, isResolvingPlay = false, closeScreen = true) }
                return@launch
            }

            // 豆瓣纯推荐源（msearch:），自动启动跨源快搜与起播
            if (vodId.contains("msearch", ignoreCase = true)) {
                val targetName = fallbackVodName.ifBlank {
                    _uiState.value.vod?.vodName ?: vodId.removePrefix("msearch:")
                }
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        isResolvingPlay = false,
                        vod = Vod(
                            vodId = vodId,
                            vodName = targetName,
                            vodRemarks = "豆瓣精选",
                            siteKey = site.key,
                            siteName = site.name,
                        ),
                    )
                }
                startAutoCrossSourceResolve(targetName, shouldAutoPlay)
                return@launch
            }

            // 常规源：拉取详情
            val res = repository.getDetailContent(site, vodId)
            val vod = res.list.firstOrNull()
            if (vod == null) {
                _uiState.update { it.copy(isLoading = false, isResolvingPlay = false, error = "获取影片详情失败或已下架") }
                return@launch
            }

            // 检查历史记录以恢复进度
            val history = repository.getHistory(site.key, vod.vodId)
            val matchedFlag = vod.playFlags.firstOrNull { it.flag == history?.vodFlag }
                ?: vod.playFlags.firstOrNull()
            val matchedEp = if (history != null && matchedFlag != null) {
                matchedFlag.episodes.getOrNull(history.episodeIndex)
                    ?: matchedFlag.episodes.firstOrNull { it.name == history.episodeName }
                    ?: matchedFlag.episodes.firstOrNull()
            } else {
                matchedFlag?.episodes?.firstOrNull()
            }

            val resumeText = history?.episodeName?.takeIf { it.isNotBlank() }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    vod = vod,
                    selectedFlag = matchedFlag,
                    selectedEpisode = matchedEp,
                    resumeEpisodeText = resumeText,
                    opening = history?.opening ?: 0L,
                    ending = history?.ending ?: 0L,
                )
            }

            // 异步获取 TMDB 海报、Logo、评分与演职员
            tmdbCoordinator.loadTmdbInfo(viewModelScope, vod.vodName) { detail, imageBase ->
                _uiState.update { it.copy(tmdbDetail = detail, tmdbImageBase = imageBase) }
            }

            // 若由历史记录点击进入 (shouldAutoPlay = true)，立即起播当前集
            if (shouldAutoPlay && matchedFlag != null && matchedEp != null) {
                val resumePos = if (history != null && history.vodFlag == matchedFlag.flag && history.episodeIndex == matchedEp.index) {
                    history.position
                } else 0L
                _uiState.update { it.copy(startInFullscreen = true) }
                playEpisode(matchedFlag, matchedEp, resumePos)
            } else if (shouldAutoPlay) {
                _uiState.update { it.copy(isResolvingPlay = false, error = "未找到可播放的剧集") }
            }

            // 后台静默抓取换源列表
            triggerSearchChangeSources(vod.vodName)
        }
    }

    private fun startAutoCrossSourceResolve(vodName: String, shouldAutoPlay: Boolean) {
        sourceCoordinator.startAutoCrossSourceResolve(
            scope = viewModelScope,
            vodName = vodName,
            onStart = { msg ->
                _uiState.update { it.copy(isAutoSearching = true, autoSearchMessage = msg) }
            },
            onResolved = { targetSite, validVod, flag, ep ->
                currentSiteKey = targetSite.key
                currentVodId = validVod.vodId
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        vod = validVod,
                        currentSite = targetSite,
                        selectedFlag = flag,
                        selectedEpisode = ep,
                        isAutoSearching = false,
                        autoSearchMessage = null,
                    )
                }
                tmdbCoordinator.loadTmdbInfo(viewModelScope, validVod.vodName) { detail, imageBase ->
                    _uiState.update { it.copy(tmdbDetail = detail, tmdbImageBase = imageBase) }
                }
                if (shouldAutoPlay) {
                    _uiState.update { it.copy(startInFullscreen = true) }
                    playEpisode(flag, ep, 0L)
                }
            },
            onFailed = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isResolvingPlay = false,
                        isAutoSearching = false,
                        autoSearchMessage = null,
                        error = errorMsg,
                    )
                }
            },
        )
    }

    private fun triggerSearchChangeSources(vodName: String) {
        sourceCoordinator.triggerSearchChangeSources(
            scope = viewModelScope,
            vodName = vodName,
            currentSiteKey = currentSiteKey,
            onStart = {
                _uiState.update { it.copy(isSearchingChangeSources = true, changeSources = emptyList()) }
            },
            onResultsUpdated = { results ->
                _uiState.update { it.copy(changeSources = results) }
            },
            onFinished = {
                _uiState.update { it.copy(isSearchingChangeSources = false) }
            },
        )
    }

    fun refreshPlayback() {
        val flag = _uiState.value.selectedFlag ?: return
        val ep = _uiState.value.selectedEpisode ?: return
        val currentPosition = players.state.value.currentPosition.takeIf { it > 0L }
            ?: playbackTracker.activeSession?.lastKnownPositionMs
            ?: 0L
        _uiState.update { it.copy(toastMessage = "正在刷新播放...") }
        playEpisode(flag, ep, currentPosition)
    }

    fun startPlayback() {
        val flag = _uiState.value.selectedFlag ?: return
        val ep = _uiState.value.selectedEpisode ?: flag.episodes.firstOrNull() ?: return
        val session = playbackTracker.activeSession
        if (session != null &&
            session.hasSucceeded &&
            session.site.key == currentSiteKey &&
            session.vod.vodId == _uiState.value.vod?.vodId &&
            session.flag.flag == flag.flag &&
            session.episode.url == ep.url &&
            players.state.value.playbackState == PlaybackState.READY
        ) {
            players.resume()
            return
        }
        viewModelScope.launch {
            val history = repository.getHistory(currentSiteKey, _uiState.value.vod?.vodId ?: "")
            val resumePos = if (history != null && history.vodFlag == flag.flag && history.episodeIndex == ep.index) {
                history.position
            } else 0L
            playEpisode(flag, ep, resumePos)
        }
    }

    fun onExitFullscreen() {
        val handled = playbackTracker.handleExitFullscreen(_uiState.value.opening, _uiState.value.ending)
        if (!handled) {
            playJob?.cancel()
            _uiState.update { it.copy(isResolvingPlay = false) }
        }
    }

    fun playEpisode(flag: Flag, episode: Episode, startPosition: Long = 0L) {
        val site = _uiState.value.currentSite ?: return
        val vod = _uiState.value.vod ?: return

        playbackTracker.saveActiveSessionIfSucceeded(_uiState.value.opening, _uiState.value.ending)
        playbackTracker.clearSession()
        players.stop()

        playJob?.cancel()
        playJob = viewModelScope.launch {
            val (hasPrev, hasNext) = DetailEpisodeNav.checkHasNavEpisodes(flag, episode, _uiState.value.isEpisodeReversed)

            _uiState.update {
                it.copy(
                    selectedFlag = flag,
                    selectedEpisode = episode,
                    hasPreviousEpisode = hasPrev,
                    hasNextEpisode = hasNext,
                    isResolvingPlay = true,
                )
            }

            Timber.i("正在解析直链: site=${site.name}, flag=${flag.flag}, ep=${episode.name}")
            val result = repository.resolvePlay(site, flag.flag, episode.url)

            val primaryPlayUrl = result.playUrls.firstOrNull { it.url == result.url }
                ?: result.playUrls.firstOrNull()
                ?: PlayUrl("默认", result.url)

            _uiState.update {
                it.copy(
                    isResolvingPlay = false,
                    playUrls = result.playUrls,
                    selectedPlayUrl = primaryPlayUrl,
                )
            }

            if (result.parse == 1) {
                _uiState.update { it.copy(toastMessage = "该线路返回为网页嗅探地址，建议切换其它线路或换源") }
            }
            if (result.url.isBlank()) {
                _uiState.update { it.copy(toastMessage = "该线路解析链接为空，请尝试切换其它线路或换源") }
                return@launch
            }

            lastHeaders = result.header
            lastFormat = result.format
            playbackTracker.createSession(site, vod, flag, episode, _uiState.value.opening, _uiState.value.ending)

            val title = "${vod.vodName} - ${episode.name}"
            val op = _uiState.value.opening
            val effectiveStartPosition = if (op > 0L) maxOf(startPosition, op) else startPosition
            players.play(
                url = result.url,
                headers = result.header,
                title = title,
                startPosition = effectiveStartPosition,
                format = result.format,
            )
        }
    }

    fun selectPlayUrl(playUrl: PlayUrl) {
        if (playUrl == _uiState.value.selectedPlayUrl) return
        val site = _uiState.value.currentSite ?: return
        val vod = _uiState.value.vod ?: return
        val flag = _uiState.value.selectedFlag ?: return
        val episode = _uiState.value.selectedEpisode ?: return
        val currentPosition = players.state.value.currentPosition
        _uiState.update { it.copy(selectedPlayUrl = playUrl) }

        playbackTracker.createSession(site, vod, flag, episode, _uiState.value.opening, _uiState.value.ending)
        val title = "${vod.vodName} - ${episode.name} (${playUrl.name})"
        players.play(
            url = playUrl.url,
            headers = lastHeaders,
            title = title,
            startPosition = currentPosition,
            format = lastFormat,
        )
    }

    fun selectFlag(flag: Flag) {
        if (flag == _uiState.value.selectedFlag) return
        val currentEpIndex = _uiState.value.selectedEpisode?.index ?: 0
        val targetEp = flag.episodes.getOrNull(currentEpIndex) ?: flag.episodes.firstOrNull() ?: return
        val currentPosition = players.state.value.currentPosition
        _uiState.update { it.copy(selectedFlag = flag, selectedEpisode = targetEp) }
        if (players.state.value.isPlaying || players.state.value.playbackState == PlaybackState.BUFFERING) {
            playEpisode(flag, targetEp, currentPosition)
        }
    }

    fun selectEpisode(episode: Episode) {
        val currentFlag = _uiState.value.selectedFlag ?: return
        playEpisode(currentFlag, episode, 0L)
    }

    fun playPreviousEpisode() {
        val flag = _uiState.value.selectedFlag ?: return
        val currentEp = _uiState.value.selectedEpisode ?: return
        val prevEp = DetailEpisodeNav.calculateNavEpisode(flag, currentEp, _uiState.value.isEpisodeReversed, -1)
        if (prevEp != null) {
            selectEpisode(prevEp)
        } else {
            _uiState.update { it.copy(toastMessage = "已经是第一集了") }
        }
    }

    fun playNextEpisode(isAutoPlay: Boolean = false) {
        val flag = _uiState.value.selectedFlag ?: return
        val currentEp = _uiState.value.selectedEpisode ?: return
        val nextEp = DetailEpisodeNav.calculateNavEpisode(flag, currentEp, _uiState.value.isEpisodeReversed, 1)
        if (nextEp != null) {
            if (isAutoPlay) {
                _uiState.update { it.copy(toastMessage = "准备播放：${nextEp.name}") }
            }
            selectEpisode(nextEp)
        } else {
            _uiState.update { it.copy(toastMessage = "已经是最后一集了") }
        }
    }

    fun toggleEpisodeSort() {
        val nextReversed = !_uiState.value.isEpisodeReversed
        val flag = _uiState.value.selectedFlag
        val currentEp = _uiState.value.selectedEpisode
        val (hasPrev, hasNext) = if (flag != null && currentEp != null) {
            DetailEpisodeNav.checkHasNavEpisodes(flag, currentEp, nextReversed)
        } else false to false
        _uiState.update {
            it.copy(
                isEpisodeReversed = nextReversed,
                hasPreviousEpisode = hasPrev,
                hasNextEpisode = hasNext,
            )
        }
    }

    fun changeSource(newVod: Vod, shouldAutoPlay: Boolean = true) {
        playJob?.cancel()
        playbackTracker.saveActiveSessionIfSucceeded(_uiState.value.opening, _uiState.value.ending)
        playbackTracker.clearSession()
        players.stop()
        loadDetail(newVod.siteKey, newVod.vodId, newVod.vodName, shouldAutoPlay = shouldAutoPlay)
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun openTmdbMatchDialog() {
        val vodName = _uiState.value.vod?.vodName.orEmpty()
        val currentTitle = _uiState.value.tmdbDetail?.displayTitle
        tmdbCoordinator.openTmdbMatchDialog(
            scope = viewModelScope,
            vodName = vodName,
            currentDisplayTitle = currentTitle,
            onReady = { initialKeyword, isConfigured, imageBase ->
                _uiState.update {
                    it.copy(
                        showTmdbMatchDialog = true,
                        tmdbSearchKeyword = initialKeyword,
                        tmdbSearchResults = emptyList(),
                        tmdbSearchError = null,
                        isTmdbConfigured = isConfigured,
                        tmdbImageBase = imageBase,
                    )
                }
            },
            onAutoSearch = { keyword ->
                searchTmdb(keyword)
            },
        )
    }

    fun closeTmdbMatchDialog() {
        _uiState.update {
            it.copy(
                showTmdbMatchDialog = false,
                isSearchingTmdb = false,
            )
        }
    }

    fun searchTmdb(query: String) {
        tmdbCoordinator.searchTmdb(
            scope = viewModelScope,
            query = query,
            onStart = { trimmed ->
                _uiState.update {
                    it.copy(
                        isSearchingTmdb = true,
                        tmdbSearchKeyword = trimmed,
                        tmdbSearchError = null,
                        isTmdbConfigured = true,
                    )
                }
            },
            onSuccess = { results, errorMsg ->
                _uiState.update {
                    it.copy(
                        isSearchingTmdb = false,
                        tmdbSearchResults = results,
                        tmdbSearchError = errorMsg,
                    )
                }
            },
            onError = { errorMsg, isNotConfigured ->
                _uiState.update {
                    it.copy(
                        isSearchingTmdb = false,
                        isTmdbConfigured = !isNotConfigured,
                        tmdbSearchError = errorMsg,
                    )
                }
            },
        )
    }

    fun applyTmdbMatch(media: TmdbMedia) {
        val vodName = _uiState.value.vod?.vodName.orEmpty()
        tmdbCoordinator.applyTmdbMatch(
            scope = viewModelScope,
            media = media,
            vodName = vodName,
            onStart = {
                _uiState.update { it.copy(isSearchingTmdb = true) }
            },
            onSuccess = { detail, imageBase ->
                _uiState.update {
                    it.copy(
                        tmdbDetail = detail,
                        tmdbImageBase = imageBase,
                        showTmdbMatchDialog = false,
                        isSearchingTmdb = false,
                        toastMessage = "已修正匹配为《${detail.displayTitle}》",
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isSearchingTmdb = false,
                        toastMessage = errorMsg,
                    )
                }
            },
        )
    }

    fun setOpening() {
        val duration = players.state.value.duration
        val position = players.state.value.currentPosition
        if (position <= 0L || duration <= 0L) {
            _uiState.update { it.copy(toastMessage = "请在视频播放时点击设置片头") }
            return
        }
        val limit = DetailOpEdPolicy.getOpEdLimit(duration)
        if (position > limit) {
            val limitMinutes = limit / 60_000L
            _uiState.update { it.copy(toastMessage = "片头时间过长，需在 $limitMinutes 分钟以内") }
            return
        }
        _uiState.update {
            it.copy(
                opening = position,
                toastMessage = "已设片头：${Players.formatDuration(position)}",
            )
        }
        persistOpEd(opening = position, ending = _uiState.value.ending)
    }

    fun resetOpening() {
        if (_uiState.value.opening <= 0L) {
            _uiState.update { it.copy(toastMessage = "暂未设置片头") }
            return
        }
        _uiState.update {
            it.copy(
                opening = 0L,
                toastMessage = "已清除片头",
            )
        }
        persistOpEd(opening = 0L, ending = _uiState.value.ending)
    }

    fun setEnding() {
        val duration = players.state.value.duration
        val position = players.state.value.currentPosition
        if (position <= 0L || duration <= 0L) {
            _uiState.update { it.copy(toastMessage = "请在视频播放时点击设置片尾") }
            return
        }
        val endingMs = duration - position
        val limit = DetailOpEdPolicy.getOpEdLimit(duration)
        if (endingMs <= 0L || endingMs > limit) {
            val limitMinutes = limit / 60_000L
            _uiState.update { it.copy(toastMessage = "片尾时间需在剧末 $limitMinutes 分钟以内") }
            return
        }
        _uiState.update {
            it.copy(
                ending = endingMs,
                toastMessage = "已设片尾：${Players.formatDuration(endingMs)}",
            )
        }
        persistOpEd(opening = _uiState.value.opening, ending = endingMs)
    }

    fun resetEnding() {
        if (_uiState.value.ending <= 0L) {
            _uiState.update { it.copy(toastMessage = "暂未设置片尾") }
            return
        }
        _uiState.update {
            it.copy(
                ending = 0L,
                toastMessage = "已清除片尾",
            )
        }
        persistOpEd(opening = _uiState.value.opening, ending = 0L)
    }

    private fun persistOpEd(opening: Long, ending: Long) {
        val site = _uiState.value.currentSite ?: return
        val vod = _uiState.value.vod ?: return
        playbackTracker.persistOpEd(
            site = site,
            vod = vod,
            flag = _uiState.value.selectedFlag,
            episode = _uiState.value.selectedEpisode,
            opening = opening,
            ending = ending,
        )
    }

    override fun onCleared() {
        super.onCleared()
        playJob?.cancel()
        sourceCoordinator.cancelAll()
        playbackTracker.handleCleared(_uiState.value.opening, _uiState.value.ending)
    }

    companion object {
        fun calculateNavEpisode(
            flag: Flag,
            currentEpisode: Episode,
            isReversed: Boolean,
            offset: Int,
        ): Episode? = DetailEpisodeNav.calculateNavEpisode(flag, currentEpisode, isReversed, offset)

        fun checkHasNavEpisodes(
            flag: Flag,
            currentEpisode: Episode,
            isReversed: Boolean,
        ): Pair<Boolean, Boolean> = DetailEpisodeNav.checkHasNavEpisodes(flag, currentEpisode, isReversed)

        fun getOpEdLimit(duration: Long): Long = DetailOpEdPolicy.getOpEdLimit(duration)
    }
}
