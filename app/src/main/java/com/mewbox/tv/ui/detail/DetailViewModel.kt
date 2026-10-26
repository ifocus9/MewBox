package com.mewbox.tv.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.Players
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val vod: Vod? = null,
    val currentSite: Site? = null,
    val selectedFlag: Flag? = null,
    val selectedEpisode: Episode? = null,
    val playUrls: List<com.mewbox.tv.bean.PlayUrl> = emptyList(),
    val selectedPlayUrl: com.mewbox.tv.bean.PlayUrl? = null,
    val isResolvingPlay: Boolean = false,
    val isMsearch: Boolean = false,
    val isAutoSearching: Boolean = false,
    val autoSearchMessage: String? = null,
    val changeSources: List<Vod> = emptyList(),
    val isSearchingChangeSources: Boolean = false,
    val showChangeSourceDialog: Boolean = false,
    val error: String? = null,
    val toastMessage: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: SiteRepository,
    val players: Players,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var currentSiteKey: String = savedStateHandle["siteKey"] ?: ""
    private var currentVodId: String = savedStateHandle["vodId"] ?: ""
    private val initialVodName: String = savedStateHandle["vodName"] ?: ""

    private val _uiState = MutableStateFlow(
        DetailUiState(
            isMsearch = currentVodId.startsWith("msearch:"),
            vod = if (initialVodName.isNotEmpty()) Vod(vodId = currentVodId, vodName = initialVodName) else null,
        )
    )
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var autoSearchJob: Job? = null
    private var changeSourceJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        loadDetail(currentSiteKey, currentVodId, initialVodName)
    }

    fun loadDetail(siteKey: String, vodId: String, fallbackVodName: String = "") {
        currentSiteKey = siteKey
        currentVodId = vodId

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    isMsearch = vodId.startsWith("msearch:"),
                )
            }

            val site = repository.getSite(siteKey)
            if (site == null) {
                _uiState.update { it.copy(isLoading = false, error = "站点不存在或已被禁用") }
                return@launch
            }

            _uiState.update { it.copy(currentSite = site) }

            // 如果是豆瓣纯推荐源（msearch:），没有直链详情，自动启动跨源快搜与起播
            if (vodId.startsWith("msearch:")) {
                val targetName = fallbackVodName.ifBlank {
                    _uiState.value.vod?.vodName ?: vodId.removePrefix("msearch:")
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        vod = Vod(
                            vodId = vodId,
                            vodName = targetName,
                            vodRemarks = "豆瓣精选",
                            siteKey = site.key,
                            siteName = site.name,
                        ),
                    )
                }
                startAutoCrossSourceResolve(targetName)
                return@launch
            }

            // 常规源：拉取详情
            val res = repository.getDetailContent(site, vodId)
            val vod = res.list.firstOrNull()
            if (vod == null) {
                _uiState.update { it.copy(isLoading = false, error = "获取影片详情失败或已下架") }
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

            _uiState.update {
                it.copy(
                    isLoading = false,
                    vod = vod,
                    selectedFlag = matchedFlag,
                    selectedEpisode = matchedEp,
                )
            }

            // 如果有可播放集数，起播当前集（若有历史记录则带上断点续播位置）
            if (matchedFlag != null && matchedEp != null) {
                val resumePos = if (history != null && history.vodFlag == matchedFlag.flag && history.episodeIndex == matchedEp.index) {
                    history.position
                } else 0L
                playEpisode(matchedFlag, matchedEp, resumePos)
            }

            // 后台静默抓取换源列表
            triggerSearchChangeSources(vod.vodName)
        }
    }

    /**
     * 豆瓣纯推荐源自动跨源检索首个可用播放源起播
     */
    private fun startAutoCrossSourceResolve(vodName: String) {
        if (vodName.isBlank()) return
        autoSearchJob?.cancel()
        autoSearchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAutoSearching = true,
                    autoSearchMessage = "正在为您跨源检索《$vodName》可用播放源...",
                )
            }

            var resolvedFirst = false
            repository.quickSearch(vodName).collect { foundVod ->
                // 加入换源备选列表
                _uiState.update { state ->
                    if (state.changeSources.none { it.siteKey == foundVod.siteKey && it.vodId == foundVod.vodId }) {
                        state.copy(changeSources = state.changeSources + foundVod)
                    } else state
                }

                if (!resolvedFirst) {
                    val targetSite = repository.getSite(foundVod.siteKey)
                    if (targetSite != null) {
                        val detailRes = repository.getDetailContent(targetSite, foundVod.vodId)
                        val fullVod = detailRes.list.firstOrNull()
                        if (fullVod != null && fullVod.playFlags.isNotEmpty()) {
                            val flag = fullVod.playFlags.first()
                            val ep = flag.episodes.firstOrNull()
                            if (ep != null) {
                                resolvedFirst = true
                                _uiState.update {
                                    it.copy(
                                        isAutoSearching = false,
                                        autoSearchMessage = null,
                                        vod = fullVod,
                                        currentSite = targetSite,
                                        selectedFlag = flag,
                                        selectedEpisode = ep,
                                        toastMessage = "已自动匹配到【${targetSite.name}】并起播",
                                    )
                                }
                                currentSiteKey = targetSite.key
                                currentVodId = fullVod.vodId
                                playEpisode(flag, ep, 0L)
                            }
                        }
                    }
                }
            }

            if (!resolvedFirst) {
                _uiState.update {
                    it.copy(
                        isAutoSearching = false,
                        autoSearchMessage = null,
                        toastMessage = "全网暂未检索到可直接播放的源",
                    )
                }
            }
        }
    }

    /**
     * 触发后台换源检索
     */
    fun triggerSearchChangeSources(vodName: String) {
        if (vodName.isBlank()) return
        changeSourceJob?.cancel()
        changeSourceJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearchingChangeSources = true) }
            repository.quickSearch(vodName).collect { foundVod ->
                _uiState.update { state ->
                    if (state.changeSources.none { it.siteKey == foundVod.siteKey && it.vodId == foundVod.vodId }) {
                        state.copy(changeSources = state.changeSources + foundVod)
                    } else state
                }
            }
            _uiState.update { it.copy(isSearchingChangeSources = false) }
        }
    }

    /**
     * 播放指定线路的单集
     */
    fun playEpisode(flag: Flag, episode: Episode, startPosition: Long = 0L) {
        val site = _uiState.value.currentSite ?: return
        val vod = _uiState.value.vod ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedFlag = flag,
                    selectedEpisode = episode,
                    isResolvingPlay = true,
                )
            }

            Timber.i("正在解析直链: site=${site.name}, flag=${flag.flag}, ep=${episode.name}")
            saveCurrentHistory(flag, episode, startPosition)
            val result = repository.resolvePlay(site, flag.flag, episode.url)
            val primaryPlayUrl = result.playUrls.firstOrNull { it.url == result.url }
                ?: result.playUrls.firstOrNull()
                ?: com.mewbox.tv.bean.PlayUrl("默认", result.url)

            _uiState.update {
                it.copy(
                    isResolvingPlay = false,
                    playUrls = result.playUrls,
                    selectedPlayUrl = primaryPlayUrl,
                )
            }

            if (result.parse == 1) {
                _uiState.update { it.copy(toastMessage = "该线路返回为网页嗅探地址（parse=1），当前为原生直链播放内核，建议切换其它线路或点击【换源】") }
            }
            if (result.url.isBlank()) {
                _uiState.update { it.copy(toastMessage = "该线路解析链接为空，请尝试切换其它线路或换源") }
                return@launch
            }

            lastHeaders = result.header
            val title = "${vod.vodName} - ${episode.name}"
            players.play(
                url = result.url,
                headers = result.header,
                title = title,
                startPosition = startPosition,
            )

            // 记录到播放历史
            saveCurrentHistory(flag, episode, startPosition)
        }
    }

    private var lastHeaders: Map<String, String> = emptyMap()

    /**
     * 切换同集不同清晰度（网盘多清晰度流，无缝续播当前进度）
     */
    fun selectPlayUrl(playUrl: com.mewbox.tv.bean.PlayUrl) {
        if (playUrl == _uiState.value.selectedPlayUrl) return
        val vod = _uiState.value.vod ?: return
        val episode = _uiState.value.selectedEpisode ?: return
        val currentPosition = players.state.value.currentPosition
        _uiState.update { it.copy(selectedPlayUrl = playUrl) }
        val title = "${vod.vodName} - ${episode.name} (${playUrl.name})"
        players.play(
            url = playUrl.url,
            headers = lastHeaders,
            title = title,
            startPosition = currentPosition,
        )
    }

    /**
     * 切换线路（尽量保持当前集数索引并续播）
     */
    fun selectFlag(flag: Flag) {
        if (flag == _uiState.value.selectedFlag) return
        val currentEpIndex = _uiState.value.selectedEpisode?.index ?: 0
        val targetEp = flag.episodes.getOrNull(currentEpIndex) ?: flag.episodes.firstOrNull() ?: return
        val currentPosition = players.state.value.currentPosition
        playEpisode(flag, targetEp, currentPosition)
    }

    /**
     * 切换选集（从头开始播放）
     */
    fun selectEpisode(episode: Episode) {
        val currentFlag = _uiState.value.selectedFlag ?: return
        playEpisode(currentFlag, episode, 0L)
    }

    /**
     * 手动切换到指定源
     */
    fun changeSource(newVod: Vod) {
        _uiState.update { it.copy(showChangeSourceDialog = false) }
        players.stop()
        loadDetail(newVod.siteKey, newVod.vodId, newVod.vodName)
    }

    fun openChangeSourceDialog() {
        _uiState.update { it.copy(showChangeSourceDialog = true) }
        val name = _uiState.value.vod?.vodName ?: ""
        if (_uiState.value.changeSources.isEmpty() && name.isNotBlank()) {
            triggerSearchChangeSources(name)
        }
    }

    fun closeChangeSourceDialog() {
        _uiState.update { it.copy(showChangeSourceDialog = false) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun saveCurrentHistory(flag: Flag, episode: Episode, positionMs: Long) {
        val site = _uiState.value.currentSite ?: return
        val vod = _uiState.value.vod ?: return
        ioScope.launch {
            try {
                val history = History(
                    key = History.key(site.key, vod.vodId),
                    siteKey = site.key,
                    siteName = site.name,
                    vodId = vod.vodId,
                    vodName = vod.vodName,
                    vodPic = vod.vodPic,
                    vodFlag = flag.flag,
                    episodeName = episode.name,
                    episodeUrl = episode.url,
                    episodeIndex = episode.index,
                    position = positionMs,
                    duration = players.state.value.duration,
                )
                repository.saveHistory(history)
                Timber.i("成功记录播放历史: ${vod.vodName} - ${episode.name}, position=$positionMs, key=${history.key}")
            } catch (e: Exception) {
                Timber.e(e, "保存播放历史失败")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 保存离开时的当前进度
        val state = _uiState.value
        val flag = state.selectedFlag
        val ep = state.selectedEpisode
        if (flag != null && ep != null) {
            saveCurrentHistory(flag, ep, players.state.value.currentPosition)
        }
        players.stop()
    }
}
