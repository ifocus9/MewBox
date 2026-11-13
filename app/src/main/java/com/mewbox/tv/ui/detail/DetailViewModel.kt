package com.mewbox.tv.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.PlayerTrack
import com.mewbox.tv.player.Players
import com.mewbox.tv.util.AppToast
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
    private var activeConfigId: String = ""
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
    private var prefetchJob: Job? = null
    private var tmdbJob: Job? = null
    private var tmdbRunningName: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var lastFormat: String = ""

    private fun startTmdbLoad(name: String) {
        val cleaned = TmdbClient.cleanTitle(name)
        Timber.d("startTmdbLoad: name=$name, cleaned=$cleaned")
        if (cleaned.isBlank()) return
        // 同一片名已在跑时不要重复取消重启，否则每次重起都会把 searchMulti 的超时预算清零
        if (tmdbJob?.isActive == true && tmdbRunningName.equals(cleaned, ignoreCase = true)) {
            Timber.d("startTmdbLoad: 同名任务仍在运行，跳过重复启动: $cleaned")
            return
        }
        tmdbJob?.cancel()
        tmdbRunningName = cleaned
        tmdbJob = tmdbCoordinator.loadTmdbInfo(
            scope = viewModelScope,
            vodName = name,
            onQuick = { poster, backdrop ->
                _uiState.update { it.copy(quickPoster = poster, quickBackdrop = backdrop) }
            },
            onLoaded = { detail, imageBase ->
                _uiState.update {
                    it.copy(
                        tmdbDetail = detail,
                        tmdbImageBase = imageBase,
                        quickPoster = TmdbClient.imageUrl(imageBase, detail.posterPath, "w780").ifEmpty { null },
                        quickBackdrop = TmdbClient.imageUrl(imageBase, detail.backdropPath, "w1280").ifEmpty { null },
                    )
                }
            },
        )
    }

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
                val msg = if (siteKey == SiteRepository.PUSH_KEY) {
                    "未找到可用的推送源（请先添加包含推送能力的 Node.js 订阅源）"
                } else {
                    "站点不存在或已被禁用"
                }
                _uiState.update { it.copy(isLoading = false, isResolvingPlay = false, error = msg) }
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
                // 优先以权威豆瓣片名并行起跑 TMDB
                if (targetName.isNotBlank()) {
                    startTmdbLoad(targetName)
                }
                startAutoCrossSourceResolve(targetName, shouldAutoPlay)
                return@launch
            }

            // 常规源：立即使用已知入口片名与站源详情并行起跑 TMDB，无需等站源 RTT
            val kickoffName = fallbackVodName.ifBlank { initialVodName }
            if (kickoffName.isNotBlank()) {
                startTmdbLoad(kickoffName)
            }

            // 常规源：拉取详情
            val res = repository.getDetailContent(site, vodId)
            val rawVod = res.list.firstOrNull()
            if (rawVod == null) {
                _uiState.update { it.copy(isLoading = false, isResolvingPlay = false, error = "获取影片详情失败或已下架") }
                return@launch
            }
            val vod = rawVod.copy(
                vodName = if (
                    fallbackVodName.isNotBlank() &&
                    (rawVod.vodName.isBlank() ||
                        rawVod.vodName == vodId ||
                        rawVod.vodName.startsWith("http://", ignoreCase = true) ||
                        rawVod.vodName.startsWith("https://", ignoreCase = true) ||
                        rawVod.vodName.startsWith("push://", ignoreCase = true))
                ) {
                    fallbackVodName
                } else {
                    rawVod.vodName
                },
                vodPic = rawVod.vodPic.ifBlank { initialVodPic },
            )

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

            // 加载该视频历史保存的字幕、音轨、视轨用户偏好并注入播放器
            val currentConfigId = repository.getActiveConfigId().also { activeConfigId = it }
            val historyKey = History.key(site.key, vod.vodId, currentConfigId)
            val preferences = repository.getTrackPreferences(historyKey).ifEmpty {
                repository.getTrackPreferences(History.key(site.key, vod.vodId))
            }
            players.setPlaybackSession(historyKey, preferences)

            // 校验站源真实片名与并行起跑片名：
            // 若站源片名清洗后与起跑片名不同（或者起跑时无片名），则按真实片名重新拉取；
            // 若清洗后一致，则保留已经起跑的 TMDB 流程，不重复发起请求
            val currentCleaned = TmdbClient.cleanTitle(kickoffName)
            val realCleaned = TmdbClient.cleanTitle(vod.vodName)
            if (currentCleaned.isBlank() || !currentCleaned.equals(realCleaned, ignoreCase = true)) {
                startTmdbLoad(vod.vodName)
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
            } else if (matchedFlag != null && matchedEp != null) {
                // 常规进入海报详情页：后台预解析当前集清晰度列表，使详情页即时展示画质选项并提升后续起播速度
                prefetchPlayUrls(site, matchedFlag, matchedEp)
            }
        }
    }

    private fun prefetchPlayUrls(site: Site, flag: Flag, episode: Episode) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            _uiState.update { it.copy(isResolvingPlay = true) }
            try {
                Timber.d("预解析清晰度: site=${site.name}, flag=${flag.flag}, ep=${episode.name}")
                val result = repository.resolvePlay(site, flag.flag, episode.url)
                val primaryPlayUrl = result.playUrls.firstOrNull { it.url == result.url }
                    ?: result.playUrls.firstOrNull()
                    ?: PlayUrl("默认", result.url)

                lastHeaders = result.header
                lastFormat = result.format

                _uiState.update {
                    it.copy(
                        isResolvingPlay = false,
                        playUrls = result.playUrls,
                        selectedPlayUrl = primaryPlayUrl,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "预解析清晰度失败: site=${site.name}, flag=${flag.flag}, ep=${episode.name}")
                _uiState.update { it.copy(isResolvingPlay = false) }
            }
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
                // 仅当之前未成功启动 TMDB 流程且无详情时才补充启动，避免采集站脏标题推翻豆瓣权威名
                if (tmdbJob == null && _uiState.value.tmdbDetail == null) {
                    startTmdbLoad(vodName.ifBlank { validVod.vodName })
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

    /**
     * 长按影片名手动触发快速搜索换源列表
     */
    fun triggerChangeSourcesSearch() {
        val vod = _uiState.value.vod ?: return
        val vodName = vod.vodName.trim()
        if (vodName.isBlank()) return
        if (_uiState.value.isSearchingChangeSources) {
            _uiState.update { it.copy(toastMessage = "正在检索换源中...") }
            return
        }
        _uiState.update { it.copy(toastMessage = "正在检索《$vodName》换源...") }
        triggerSearchChangeSources(vodName)
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
            onFinished = { count ->
                _uiState.update {
                    it.copy(
                        isSearchingChangeSources = false,
                        toastMessage = if (count > 0) "已检索到 $count 个可用换源" else "未检索到其他可用片源",
                    )
                }
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

            val site = _uiState.value.currentSite
            val vod = _uiState.value.vod
            val selectedUrl = _uiState.value.selectedPlayUrl
            // 若预解析已完成且有效，直接起播以实现 0 RTT 极速秒播
            if (site != null && vod != null && selectedUrl != null && selectedUrl.url.isNotBlank() && !Players.isIsoFormat(selectedUrl.url, ep.name, lastFormat)) {
                prefetchJob?.cancel()
                val (hasPrev, hasNext) = DetailEpisodeNav.checkHasNavEpisodes(flag, ep, _uiState.value.isEpisodeReversed)
                _uiState.update {
                    it.copy(
                        selectedFlag = flag,
                        selectedEpisode = ep,
                        hasPreviousEpisode = hasPrev,
                        hasNextEpisode = hasNext,
                        isResolvingPlay = false,
                    )
                }
                playbackTracker.createSession(site, vod, flag, ep, _uiState.value.opening, _uiState.value.ending)
                val title = "${vod.vodName} - ${ep.name} (${selectedUrl.name})"
                val effectiveStartPosition = if (_uiState.value.opening > 0L) maxOf(resumePos, _uiState.value.opening) else resumePos
                players.play(
                    url = selectedUrl.url,
                    headers = lastHeaders,
                    title = title,
                    startPosition = effectiveStartPosition,
                    format = lastFormat,
                )
            } else {
                playEpisode(flag, ep, resumePos)
            }
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

        prefetchJob?.cancel()

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

            if (result.needSniff) {
                Timber.w("线路返回网页地址，已跳过播放: flag=${flag.flag}, url=${result.url}")
                val msg = "该线路为网页地址，暂不支持播放，请切换其它线路或换源"
                _uiState.update { it.copy(toastMessage = msg) }
                AppToast.show(msg)
                players.reportError(msg, result.url)
                return@launch
            }
            if (result.url.isBlank()) {
                val msg = "该线路解析链接为空，请尝试切换其它线路或换源"
                _uiState.update { it.copy(toastMessage = msg) }
                AppToast.show(msg)
                players.reportError(msg)
                return@launch
            }

            if (Players.isIsoFormat(result.url, episode.name, result.format)) {
                Timber.w("检测到 ISO 原盘镜像，拦截播放: site=${site.name}, flag=${flag.flag}, ep=${episode.name}, url=${result.url}")
                val msg = "检测到该资源为 ISO 原盘镜像，内置播放器暂不支持，请切换其它线路或格式"
                _uiState.update { it.copy(toastMessage = msg) }
                AppToast.show(msg)
                players.reportError(msg, result.url)
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

        if (Players.isIsoFormat(playUrl.url, episode.name, lastFormat)) {
            val msg = "检测到该清晰度为 ISO 原盘镜像，内置播放器暂不支持"
            _uiState.update { it.copy(toastMessage = msg) }
            AppToast.show(msg)
            players.reportError(msg, playUrl.url)
            return
        }

        val isPlayerActive = players.state.value.playUrl.isNotEmpty() ||
            players.state.value.playbackState != PlaybackState.IDLE
        if (isPlayerActive) {
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
        val switchMsg = "已切换画质：${playUrl.name}"
        _uiState.update { it.copy(toastMessage = switchMsg) }
        AppToast.show(switchMsg)
    }

    fun selectFlag(flag: Flag, playImmediately: Boolean = false) {
        if (flag == _uiState.value.selectedFlag) return
        val currentEp = _uiState.value.selectedEpisode
        val currentEpIndex = currentEp?.index ?: 0
        val targetEp = flag.episodes.getOrNull(currentEpIndex)
            ?: (if (currentEp != null) flag.episodes.firstOrNull { it.name == currentEp.name } else null)
            ?: flag.episodes.firstOrNull()
            ?: return
        val currentPosition = players.state.value.currentPosition
        val isPlayerActive = players.state.value.playUrl.isNotEmpty() ||
            players.state.value.playbackState != PlaybackState.IDLE ||
            _uiState.value.isResolvingPlay
        val shouldPlay = playImmediately || isPlayerActive
        prefetchJob?.cancel()
        _uiState.update {
            it.copy(
                selectedFlag = flag,
                selectedEpisode = targetEp,
                playUrls = emptyList(),
                selectedPlayUrl = null,
                toastMessage = if (shouldPlay) "切换线路：${flag.flag}" else it.toastMessage,
            )
        }
        if (shouldPlay) {
            playEpisode(flag, targetEp, currentPosition)
        } else {
            val site = _uiState.value.currentSite
            if (site != null) {
                prefetchPlayUrls(site, flag, targetEp)
            }
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

        val currentSite = _uiState.value.currentSite
        val currentVod = _uiState.value.vod
        if (currentSite != null && currentVod != null) {
            viewModelScope.launch {
                val configId = activeConfigId.ifBlank { repository.getActiveConfigId().also { activeConfigId = it } }
                val oldKey = History.key(currentSite.key, currentVod.vodId, configId)
                val newKey = History.key(newVod.siteKey, newVod.vodId, configId)
                val targetSite = repository.getSite(newVod.siteKey) ?: Site(newVod.siteKey, newVod.siteKey, 4, "")
                repository.copyHistoryAndPreferences(oldKey, newKey, targetSite, newVod)
                loadDetail(newVod.siteKey, newVod.vodId, newVod.vodName, shouldAutoPlay = shouldAutoPlay)
            }
        } else {
            loadDetail(newVod.siteKey, newVod.vodId, newVod.vodName, shouldAutoPlay = shouldAutoPlay)
        }
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
                        quickPoster = TmdbClient.imageUrl(imageBase, detail.posterPath, "w780").ifEmpty { null },
                        quickBackdrop = TmdbClient.imageUrl(imageBase, detail.backdropPath, "w1280").ifEmpty { null },
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

    fun getEffectiveHistoryKey(): String {
        val siteKey = _uiState.value.currentSite?.key ?: currentSiteKey
        val vodId = _uiState.value.vod?.vodId ?: currentVodId
        val configId = activeConfigId.ifBlank { settingsRepository.currentConfigId }
        return History.key(siteKey, vodId, configId)
    }

    fun selectTrack(track: PlayerTrack) {
        if (!track.isSupported) {
            com.mewbox.tv.util.AppToast.show("当前设备暂不支持该音轨解码 (${track.name})")
            return
        }
        val key = getEffectiveHistoryKey()
        val success = players.selectTrack(track) { pref ->
            viewModelScope.launch {
                repository.saveTrackPreference(pref.copy(key = key))
            }
        }
        if (!success) {
            com.mewbox.tv.util.AppToast.show("当前设备暂不支持该音轨解码，已保持原音轨")
        }
    }

    fun setTrackTypeDisabled(type: Int, disabled: Boolean) {
        val key = getEffectiveHistoryKey()
        players.setTrackTypeDisabled(type, disabled) { pref ->
            viewModelScope.launch {
                repository.saveTrackPreference(pref.copy(key = key))
            }
        }
    }

    fun resetTrackTypeToDefault(type: Int) {
        val key = getEffectiveHistoryKey()
        players.resetTrackTypeToDefault(type) {
            viewModelScope.launch {
                repository.removeTrackPreference(key, type)
            }
        }
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
