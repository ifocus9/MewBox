package com.mewbox.tv.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.domain.HotRankingRepository
import com.mewbox.tv.domain.SiteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SiteResultItem(
    val siteKey: String?, // null 为“全部”
    val siteName: String,
    val count: Int,
)

data class SearchUiState(
    val keyword: String = "",
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val results: List<Vod> = emptyList(),
    val history: List<String> = emptyList(),
    val selectedSiteKey: String? = null,
    val searchTargetSiteKey: String? = null,
    // 搜索结果页默认展示两列网格样式
    val isDoubleColumn: Boolean = true,
    val availableSites: List<Site> = emptyList(),
    val hotTvList: List<String> = emptyList(),
    val hotMovieList: List<String> = emptyList(),
    val hotVarietyList: List<String> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: SiteRepository,
    private val siteDao: SiteDao,
    private val router: SpiderRouter,
    private val hotRankingRepository: HotRankingRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SearchUiState(
            history = settingsRepository.searchHistory,
            hotTvList = DEFAULT_HOT_TV,
            hotMovieList = DEFAULT_HOT_MOVIE,
            hotVarietyList = DEFAULT_HOT_VARIETY,
        )
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadAvailableSites()
        loadHotRankings()
        observeSearchHistory()
        val initialKeyword = savedStateHandle.get<String>("keyword")?.trim().orEmpty()
        if (initialKeyword.isNotEmpty()) {
            search(initialKeyword)
        }
    }

    private fun observeSearchHistory() {
        viewModelScope.launch {
            settingsRepository.searchHistoryFlow.collect { historyList ->
                _uiState.update { it.copy(history = historyList) }
            }
        }
    }

    private fun loadAvailableSites() {
        viewModelScope.launch(Dispatchers.IO) {
            siteDao.observeAll().collect { all ->
                val available = all.filter { it.enabled && !it.hide && router.isSupported(it) }
                _uiState.update { it.copy(availableSites = available) }
            }
        }
    }

    private fun loadHotRankings() {
        viewModelScope.launch {
            val rankings = hotRankingRepository.getHotRankings()
            _uiState.update {
                it.copy(
                    hotTvList = if (rankings.tvList.isNotEmpty()) rankings.tvList else DEFAULT_HOT_TV,
                    hotMovieList = if (rankings.movieList.isNotEmpty()) rankings.movieList else DEFAULT_HOT_MOVIE,
                    hotVarietyList = if (rankings.varietyList.isNotEmpty()) rankings.varietyList else DEFAULT_HOT_VARIETY,
                )
            }
        }
    }

    fun onKeywordChange(newKeyword: String) {
        _uiState.update { it.copy(keyword = newKeyword) }
    }

    fun exitSearchResult() {
        searchJob?.cancel()
        _uiState.update { it.copy(hasSearched = false, isSearching = false) }
    }

    fun toggleColumn() {
        _uiState.update { it.copy(isDoubleColumn = !it.isDoubleColumn) }
    }

    fun selectSite(siteKey: String?) {
        _uiState.update { it.copy(selectedSiteKey = siteKey) }
    }

    fun setSearchTargetSite(siteKey: String?) {
        _uiState.update { it.copy(searchTargetSiteKey = siteKey) }
    }

    /** 处理配置/网页动作类条目（跳过海报详情页直接执行） */
    fun handleAction(vod: Vod) {
        val siteKey = vod.siteKey
        val action = vod.effectiveAction
        viewModelScope.launch {
            val site = repository.getSite(siteKey)
            if (site != null) {
                repository.action(site, action)
            }
        }
    }

    /** 手动切换单个站点的可搜索状态（对齐 MewPlayer onSearchClick：可搜索源在 1 和 2 之间切换，searchable=0 则不切换） */
    fun toggleSiteSearchable(site: Site) {
        if (!site.isSearchSupport) return
        viewModelScope.launch(Dispatchers.IO) {
            val next = if (site.searchable == 1) 2 else 1
            val updated = site.copy(searchable = next)
            siteDao.update(updated)
        }
    }

    fun search(keywordToSearch: String = _uiState.value.keyword, siteKey: String? = _uiState.value.searchTargetSiteKey) {
        val kw = keywordToSearch.trim()
        if (kw.isEmpty()) return

        // 加入搜索历史并持久化至 DataStore（对齐 FongMi Setting.putKeyword）
        viewModelScope.launch {
            settingsRepository.addSearchHistory(kw)
        }

        // 立即同步更新内存 UI 状态（去重前置，保留最多 20 条）
        _uiState.update { state ->
            val newHistory = (listOf(kw) + state.history.filter { it != kw }).take(20)
            state.copy(
                keyword = kw,
                hasSearched = true,
                isSearching = true,
                results = emptyList(),
                selectedSiteKey = null,
                searchTargetSiteKey = siteKey,
                history = newHistory,
            )
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val seenKeys = HashSet<String>()
            repository.searchAll(kw, siteKey).collect { newVods ->
                val uniqueVods = ArrayList<Vod>(newVods.size)
                for (vod in newVods) {
                    val key = "${vod.siteKey}_${vod.vodId}"
                    if (seenKeys.add(key)) {
                        uniqueVods.add(vod)
                    }
                }
                if (uniqueVods.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(results = state.results + uniqueVods)
                    }
                }
            }
            _uiState.update { it.copy(isSearching = false) }
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        viewModelScope.launch {
            settingsRepository.clearSearchHistory()
        }
    }

    fun deleteHistoryItem(item: String) {
        _uiState.update { state -> state.copy(history = state.history.filter { it != item }) }
        viewModelScope.launch {
            settingsRepository.deleteSearchHistory(item)
        }
    }

    companion object {
        val DEFAULT_HOT_TV = listOf("繁花", "庆余年第二季", "玫瑰的故事", "狂飙", "漫长的季节", "追风者", "三体", "莲花楼", "与凤行", "边水往事")
        val DEFAULT_HOT_MOVIE = listOf("抓娃娃", "热辣滚烫", "飞驰人生2", "周处除三害", "第二十条", "默杀", "异形：夺命舰", "封神第一部", "流浪地球2", "孤注一掷")
        val DEFAULT_HOT_VARIETY = listOf("歌手2024", "脱口秀和Ta的朋友们", "喜剧之王单口季", "披荆斩棘第四季", "乘风2024", "奔跑吧", "极限挑战", "密室大逃脱", "种地吧", "大侦探")
    }
}
