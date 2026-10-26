package com.mewbox.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.bean.VodClass
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.domain.SiteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val currentSite: Site? = null,
    val availableSites: List<Site> = emptyList(),
    val classes: List<VodClass> = emptyList(),
    val selectedClassId: String = "",
    val items: List<Vod> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val page: Int = 1,
    val pageCount: Int = 1,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SiteRepository,
    private val siteDao: SiteDao,
    private val vodConfig: VodConfig,
    private val router: SpiderRouter,
) : ViewModel() {

    private data class InternalState(
        val classes: List<VodClass> = emptyList(),
        val selectedClassId: String = "",
        val items: List<Vod> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val page: Int = 1,
        val pageCount: Int = 1,
        val error: String? = null,
    )

    private val internalState = MutableStateFlow(InternalState())
    private var loadJob: Job? = null
    private var currentLoadedSiteKey: String? = null

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeHomeSite(),
        siteDao.observeAll(),
        internalState,
    ) { homeSite, allSites, state ->
        // 当首页站点发生变更时自动加载
        if (homeSite != null && homeSite.key != currentLoadedSiteKey) {
            currentLoadedSiteKey = homeSite.key
            loadHome(homeSite)
        }
        HomeUiState(
            currentSite = homeSite,
            availableSites = allSites.filter { it.enabled && !it.hide && router.isSupported(it) },
            classes = state.classes,
            selectedClassId = state.selectedClassId,
            items = state.items,
            isLoading = state.isLoading,
            isLoadingMore = state.isLoadingMore,
            page = state.page,
            pageCount = state.pageCount,
            error = state.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun selectClass(classId: String) {
        if (internalState.value.selectedClassId == classId) return
        internalState.update { it.copy(selectedClassId = classId, page = 1, items = emptyList()) }
        loadCategory(page = 1)
    }

    fun refresh() {
        val site = uiState.value.currentSite ?: return
        loadHome(site)
    }

    fun switchHomeSite(site: Site) {
        viewModelScope.launch {
            vodConfig.setHome(site.key)
        }
    }

    fun loadMore() {
        val state = internalState.value
        if (state.isLoading || state.isLoadingMore || state.page >= state.pageCount) return
        loadCategory(page = state.page + 1)
    }

    private fun loadHome(site: Site) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            internalState.update { it.copy(isLoading = true, error = null, items = emptyList()) }
            val res = repository.getHomeContent(site)
            val classes = res.classes
            val firstClassId = classes.firstOrNull()?.typeId.orEmpty()

            internalState.update {
                it.copy(
                    classes = classes,
                    selectedClassId = firstClassId,
                    isLoading = false,
                )
            }

            if (firstClassId.isNotEmpty()) {
                loadCategory(page = 1)
            } else if (res.list.isNotEmpty()) {
                // 部分站点直接在 homeContent 返回推荐视频
                internalState.update { it.copy(items = res.list) }
            }
        }
    }

    private fun loadCategory(page: Int) {
        val site = uiState.value.currentSite ?: return
        val classId = internalState.value.selectedClassId
        if (classId.isEmpty()) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val isFirst = page == 1
            internalState.update {
                if (isFirst) it.copy(isLoading = true, error = null)
                else it.copy(isLoadingMore = true)
            }

            val res = repository.getCategoryContent(site, classId, page)
            if (res.list.isEmpty() && isFirst) {
                internalState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        items = emptyList(),
                        page = 1,
                        pageCount = 1,
                    )
                }
            } else {
                internalState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        items = if (isFirst) res.list else it.items + res.list,
                        page = res.page.coerceAtLeast(page),
                        pageCount = res.pageCount.coerceAtLeast(1),
                    )
                }
            }
        }
    }
}
