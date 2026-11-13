package com.mewbox.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Result
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.bean.VodClass
import com.mewbox.tv.bean.VodFilter
import com.mewbox.tv.bean.VodFilterValue
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.engine.nodejs.NodeRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 分类数据缓存最多保留的分类数量（超出按 LRU 淘汰） */
private const val MAX_CACHED_CLASSES = 12

data class HomeUiState(
    val currentSite: Site? = null,
    val availableSites: List<Site> = emptyList(),
    /** 站点列表是否已完成首次加载（用于区分“还没查库”和“确实没有站源”，避免冷启动误判空态） */
    val sitesLoaded: Boolean = false,
    val classes: List<VodClass> = emptyList(),
    val selectedClassId: String = "",
    /** 各分类可用的筛选维度（key = typeId） */
    val filtersByClass: Map<String, List<VodFilter>> = emptyMap(),
    /** 各分类当前选中的筛选参数（key = typeId, value = filterKey -> filterValue） */
    val selectedFiltersByClass: Map<String, Map<String, String>> = emptyMap(),
    val items: List<Vod> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isRefreshingSubscription: Boolean = false,
    /** Node.js 源正在下载/启动（此时首页留白，加载状态由全局 LoadingState 统一展示） */
    val isNodeLoading: Boolean = false,
    /** 订阅列表（首页 logo 弹出的订阅切换弹窗使用） */
    val configs: List<Config> = emptyList(),
    /** 当前激活订阅的 url */
    val activeConfigUrl: String = "",
    val page: Int = 1,
    val pageCount: Int = 1,
    val error: String? = null,
    val message: String? = null,
) {
    val currentFilters: List<VodFilter>
        get() = filtersByClass[selectedClassId].orEmpty()

    val currentSelectedFilters: Map<String, String>
        get() = selectedFiltersByClass[selectedClassId].orEmpty()

    val currentDefaultFilters: Map<String, String>
        get() = buildDefaultFiltersForClass(currentFilters)
}

private fun buildDefaultFiltersForClass(filters: List<VodFilter>): Map<String, String> {
    if (filters.isEmpty()) return emptyMap()
    val defaults = LinkedHashMap<String, String>()
    for (filter in filters) {
        val initVal = filter.init
        if (!initVal.isNullOrEmpty()) {
            defaults[filter.key] = initVal
        }
    }
    return defaults
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SiteRepository,
    private val siteDao: SiteDao,
    private val configDao: ConfigDao,
    private val vodConfig: VodConfig,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
    private val nodeRuntime: NodeRuntime,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            isNodeLoading = nodeRuntime.loading.value != null,
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    /** 单个分类的已加载结果快照 */
    private data class CategoryCache(
        val items: List<Vod>,
        val page: Int,
        val pageCount: Int,
    )

    /**
     * 分类数据缓存：切换分类时若命中则直接复用，不再重新请求。
     * key = `siteKey|typeId`（不同站点的 typeId 极易重名，带站点前缀可避免串数据）；
     * 使用 accessOrder LRU，超过 [MAX_CACHED_CLASSES] 个分类时淘汰最久未访问的。
     */
    private val categoryCache = object : LinkedHashMap<String, CategoryCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CategoryCache>?): Boolean =
            size > MAX_CACHED_CLASSES
    }

    private fun cacheKey(siteKey: String, classId: String, filters: Map<String, String>): String {
        if (filters.isEmpty()) return "$siteKey|$classId"
        val filterPart = filters.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        return "$siteKey|$classId|$filterPart"
    }

    private fun cacheCategory(
        siteKey: String,
        classId: String,
        filters: Map<String, String>,
        items: List<Vod>,
        page: Int,
        pageCount: Int,
    ) {
        if (siteKey.isEmpty() || classId.isEmpty() || items.isEmpty()) return
        categoryCache[cacheKey(siteKey, classId, filters)] = CategoryCache(items, page, pageCount)
    }

    init {
        viewModelScope.launch {
            // 冷启动：Node.js 源走完整 reload（比对 md5 → 有新版下载重启），结果在这里提示一次
            vodConfig.ensureActiveConfig()?.let { msg ->
                _uiState.update { it.copy(message = msg) }
            }
        }

        // 0. 跟随 Node.js 运行时进度：下载/启动期间首页不自己转圈，加载状态由全局 LoadingState 展示
        viewModelScope.launch {
            nodeRuntime.loading.collect { text ->
                _uiState.update { it.copy(isNodeLoading = text != null) }
            }
        }

        // 1. 监听所有可用站点列表
        viewModelScope.launch {
            siteDao.observeAll().collect { sites ->
                val available = sites.filter { it.enabled && !it.hide && router.isSupported(it) }
                _uiState.update { it.copy(availableSites = available, sitesLoaded = true) }
            }
        }

        // 1.5 监听订阅列表与激活订阅：首页 logo 点击弹出订阅切换弹窗
        viewModelScope.launch {
            combine(configDao.observeAll(), settings.configUrlFlow) { configs, active -> configs to active }
                .collect { (configs, active) ->
                    _uiState.update { it.copy(configs = configs, activeConfigUrl = active) }
                }
        }

        // 2. 监听当前首页站点、配置重载事件、Node.js 运行时就绪状态与配置同步态，驱动首页内容加载
        viewModelScope.launch {
            combine(
                repository.observeHomeSite(),
                vodConfig.configReloadFlow,
                nodeRuntime.loading,
                vodConfig.isConfiguring,
            ) { homeSite, reloadTime, nodeLoading, isConfiguring ->
                Triple(homeSite, reloadTime, nodeLoading != null || isConfiguring)
            }.collectLatest { (homeSite, _, blocked) ->
                if (homeSite != null) {
                    // 等待 Node.js 运行时就绪且配置/站点表替换完成后再加载首页内容，避免冷启动抢跑发双请求
                    if (blocked) {
                        return@collectLatest
                    }
                    loadHome(homeSite)
                } else {
                    refreshJob?.cancel()
                    categoryCache.clear()
                    _uiState.update {
                        it.copy(
                            currentSite = null,
                            classes = emptyList(),
                            selectedClassId = "",
                            filtersByClass = emptyMap(),
                            selectedFiltersByClass = emptyMap(),
                            items = emptyList(),
                            isLoading = false,
                            isRefreshing = false,
                            page = 1,
                            pageCount = 1,
                        )
                    }
                }
            }
        }
    }

    fun selectClass(classId: String) {
        val current = _uiState.value
        if (current.selectedClassId == classId) return
        val site = current.currentSite ?: return

        loadJob?.cancel()
        refreshJob?.cancel()

        val filters = current.selectedFiltersByClass[classId].orEmpty()
        // 命中缓存：直接复用已加载过的列表，不再发起请求
        val cached = categoryCache[cacheKey(site.key, classId, filters)]
        if (cached != null) {
            loadJob = null
            _uiState.update {
                it.copy(
                    selectedClassId = classId,
                    items = cached.items,
                    page = cached.page,
                    pageCount = cached.pageCount,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                )
            }
            return
        }

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedClassId = classId,
                    page = 1,
                    items = emptyList(),
                    isLoading = true,
                    error = null,
                )
            }
            loadCategory(site, classId, page = 1, filters = filters)
        }
    }

    /** 切换当前分类的某个筛选项：已选中则取消选中，未选中则选中，并立即刷新列表。 */
    fun selectFilter(filterKey: String, value: VodFilterValue) {
        val current = _uiState.value
        val site = current.currentSite ?: return
        val classId = current.selectedClassId
        if (classId.isEmpty()) return

        val oldFilters = current.selectedFiltersByClass[classId].orEmpty()
        val newFilters = LinkedHashMap(oldFilters)
        if (oldFilters[filterKey] == value.v) {
            newFilters.remove(filterKey)
        } else {
            newFilters[filterKey] = value.v
        }

        applyClassFilters(site, classId, newFilters)
    }

    /** 重置当前分类筛选条件到默认状态并刷新列表。 */
    fun resetFilters() {
        val current = _uiState.value
        val site = current.currentSite ?: return
        val classId = current.selectedClassId
        if (classId.isEmpty()) return

        val defaultFilters = buildDefaultFiltersForClass(current.filtersByClass[classId].orEmpty())
        if (defaultFilters == current.selectedFiltersByClass[classId].orEmpty()) return

        applyClassFilters(site, classId, defaultFilters)
    }

    private fun applyClassFilters(site: Site, classId: String, filters: Map<String, String>) {
        loadJob?.cancel()
        refreshJob?.cancel()

        val updatedSelected = LinkedHashMap(_uiState.value.selectedFiltersByClass)
        if (filters.isEmpty()) {
            updatedSelected.remove(classId)
        } else {
            updatedSelected[classId] = filters
        }

        val cached = categoryCache[cacheKey(site.key, classId, filters)]
        if (cached != null) {
            loadJob = null
            _uiState.update {
                it.copy(
                    selectedFiltersByClass = updatedSelected,
                    items = cached.items,
                    page = cached.page,
                    pageCount = cached.pageCount,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                )
            }
            return
        }

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedFiltersByClass = updatedSelected,
                    page = 1,
                    items = emptyList(),
                    isLoading = true,
                    isLoadingMore = false,
                    error = null,
                )
            }
            loadCategory(site, classId, page = 1, filters = filters)
        }
    }

    fun refresh() {
        val site = _uiState.value.currentSite ?: return
        loadHome(site)
    }

    /**
     * 下拉刷新：重新拉取分类导航（classes），并刷新当前分类的第一页列表数据。
     * 与 [refresh] 的区别是不清空现有内容、不走骨架屏，列表在刷新过程中保持可见。
     */
    fun pullRefresh() {
        val current = _uiState.value
        val site = current.currentSite ?: return
        // 首次加载 / 加载更多 / 已在刷新中时忽略，避免并发覆盖
        if (current.isLoading || current.isLoadingMore || current.isRefreshing) return

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                // 1. 重新拉取分类导航（站点可能新增/删除了分类）
                val home = repository.getHomeContent(site)
                val classes = home.classes.ifEmpty { _uiState.value.classes }
                val filtersByClass = if (home.classes.isNotEmpty() || home.filters.isNotEmpty()) {
                    buildFiltersByClass(home)
                } else {
                    _uiState.value.filtersByClass
                }
                val stillValid = classes.any { it.typeId == _uiState.value.selectedClassId }
                val targetClassId = if (stillValid) {
                    _uiState.value.selectedClassId
                } else {
                    classes.firstOrNull()?.typeId.orEmpty()
                }
                val validClassIds = classes.mapTo(HashSet()) { it.typeId }
                val mergedSelectedFilters = LinkedHashMap<String, Map<String, String>>().apply {
                    _uiState.value.selectedFiltersByClass.forEach { (tid, sel) ->
                        if (tid in validClassIds) put(tid, sel)
                    }
                    filtersByClass.forEach { (tid, list) ->
                        if (!containsKey(tid)) {
                            val defaults = buildDefaultFiltersForClass(list)
                            if (defaults.isNotEmpty()) put(tid, defaults)
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        classes = classes,
                        selectedClassId = targetClassId,
                        filtersByClass = filtersByClass,
                        selectedFiltersByClass = mergedSelectedFilters,
                    )
                }

                // 分类列表变化后，清掉**当前站点**下已被删除分类的缓存（其他站点的缓存不动）
                if (home.classes.isNotEmpty()) {
                    val sitePrefix = "${site.key}|"
                    val validPrefixes = home.classes.map { "${site.key}|${it.typeId}" }
                    categoryCache.keys.retainAll { key ->
                        !key.startsWith(sitePrefix) ||
                            validPrefixes.any { prefix -> key == prefix || key.startsWith("$prefix|") }
                    }
                }

                // 2. 刷新当前分类第一页
                if (targetClassId.isNotEmpty()) {
                    val activeFilters = mergedSelectedFilters[targetClassId].orEmpty()
                    val res = repository.getCategoryContent(site, targetClassId, 1, activeFilters)
                    if (res.list.isEmpty()) {
                        // 拉取失败或确实为空：保留原有列表，仅提示，避免刷成一片空白
                        _uiState.update { it.copy(message = "刷新失败，请检查网络或站点") }
                    } else {
                        val deduplicated = res.list.distinctBy { item -> "${item.siteKey.ifEmpty { site.key }}_${item.vodId}" }
                        _uiState.update {
                            it.copy(
                                items = deduplicated,
                                page = res.page.coerceAtLeast(1),
                                pageCount = res.pageCount.coerceAtLeast(1),
                                error = null,
                            )
                        }
                        // 刷新结果同步进缓存，切走再切回时看到的是最新数据
                        cacheCategory(
                            site.key,
                            targetClassId,
                            activeFilters,
                            deduplicated,
                            res.page.coerceAtLeast(1),
                            res.pageCount.coerceAtLeast(1),
                        )
                    }
                } else if (home.list.isNotEmpty()) {
                    // 无分类的站点，直接用 homeContent 返回的推荐列表
                    val deduplicated = home.list.distinctBy { item -> "${item.siteKey.ifEmpty { site.key }}_${item.vodId}" }
                    _uiState.update {
                        it.copy(items = deduplicated, page = 1, pageCount = 1)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = "刷新失败：${e.message ?: "网络异常"}")
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun switchHomeSite(site: Site) {
        viewModelScope.launch {
            vodConfig.setHome(site.key)
        }
    }

    /**
     * 首页 logo 弹窗切换订阅：导入目标订阅并刷新首页。
     * Node.js 源的加载过程由全局 loading 浮层提示，这里不再额外发提示。
     */
    fun switchSubscription(url: String) {
        viewModelScope.launch {
            try {
                val count = vodConfig.import(url)
                val msg = if (count == 0) "已切换至网页订阅源" else "订阅切换成功（共 $count 个站点）"
                _uiState.update { it.copy(message = msg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "订阅切换失败：${NodeRuntime.describe(e)}") }
            }
        }
    }

    /** 处理配置/网页动作类条目（跳过海报详情页直接执行） */
    fun handleAction(vod: Vod) {
        val site = _uiState.value.currentSite
        val siteKey = vod.siteKey.ifEmpty { site?.key.orEmpty() }
        val action = vod.effectiveAction
        viewModelScope.launch {
            val targetSite = if (siteKey.isNotEmpty() && siteKey == site?.key) site else repository.getSite(siteKey)
            if (targetSite != null) {
                val res = repository.action(targetSite, action)
                if (res.msg.isNotBlank()) {
                    _uiState.update { it.copy(message = res.msg) }
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 长按首页站源胶囊：重新拉取并刷新订阅数据，完成后重新加载首页站点 */
    fun reloadSubscription() {
        if (_uiState.value.isRefreshingSubscription) return
        loadJob?.cancel()
        refreshJob?.cancel()
        viewModelScope.launch {
            // Node.js 源有全局 loading，不再额外弹“正在刷新订阅”提示
            val nodeSource = vodConfig.isNodeSource()
            _uiState.update {
                it.copy(
                    isRefreshingSubscription = true,
                    isLoading = true,
                    items = emptyList(),
                    error = null,
                    message = if (nodeSource) null else "正在刷新订阅...",
                )
            }
            try {
                val count = vodConfig.reload()
                val msg = if (count == 0) "网页订阅源已刷新" else "订阅刷新成功（共 $count 个站点）"
                _uiState.update {
                    it.copy(
                        isRefreshingSubscription = false,
                        message = msg,
                    )
                }
            } catch (e: Exception) {
                val msg = if (e is IllegalArgumentException) {
                    e.message ?: "刷新订阅失败"
                } else {
                    "刷新订阅失败：${NodeRuntime.describe(e)}"
                }
                _uiState.update {
                    it.copy(
                        isRefreshingSubscription = false,
                        isLoading = false,
                        message = msg,
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val site = current.currentSite ?: return
        val classId = current.selectedClassId
        if (classId.isEmpty()) return
        if (current.isLoading || current.isLoadingMore || current.isRefreshing) return
        if (current.page >= current.pageCount) return

        val filters = current.currentSelectedFilters
        viewModelScope.launch {
            loadCategory(site, classId, page = current.page + 1, filters = filters)
        }
    }

    private fun loadHome(site: Site) {
        loadJob?.cancel()
        refreshJob?.cancel()
        // 站点变了：分类体系与内容都不同，旧数据没必要留着（key 已带 siteKey，清空只为省内存/防陈旧）
        categoryCache.clear()
        loadJob = viewModelScope.launch {
            // 切换站点/刷新时，首先更新 currentSite，并彻底清空旧站点的分类与数据，进入 loading 骨架屏
            _uiState.update {
                it.copy(
                    currentSite = site,
                    classes = emptyList(),
                    selectedClassId = "",
                    filtersByClass = emptyMap(),
                    selectedFiltersByClass = emptyMap(),
                    items = emptyList(),
                    isLoading = true,
                    isLoadingMore = false,
                    error = null,
                    page = 1,
                    pageCount = 1,
                )
            }

            val res = repository.getHomeContent(site)
            val classes = res.classes
            val firstClassId = classes.firstOrNull()?.typeId.orEmpty()
            val filtersByClass = buildFiltersByClass(res)
            val initialSelected = buildInitialSelectedFilters(filtersByClass)

            _uiState.update {
                it.copy(
                    classes = classes,
                    selectedClassId = firstClassId,
                    filtersByClass = filtersByClass,
                    selectedFiltersByClass = initialSelected,
                    isLoading = firstClassId.isNotEmpty(),
                )
            }

            if (firstClassId.isNotEmpty()) {
                loadCategory(
                    site = site,
                    classId = firstClassId,
                    page = 1,
                    filters = initialSelected[firstClassId].orEmpty(),
                )
            } else if (res.list.isNotEmpty()) {
                // 部分站点直接在 homeContent 返回推荐视频
                val deduplicated = res.list.distinctBy { item -> "${item.siteKey.ifEmpty { site.key }}_${item.vodId}" }
                _uiState.update {
                    it.copy(
                        items = deduplicated,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun buildFiltersByClass(res: Result): Map<String, List<VodFilter>> {
        val map = LinkedHashMap<String, List<VodFilter>>()
        res.filters.forEach { (typeId, list) ->
            val valid = list.filter { it.values.isNotEmpty() }
            if (valid.isNotEmpty()) {
                map[typeId] = valid
            }
        }
        res.classes.forEach { vodClass ->
            if (!map.containsKey(vodClass.typeId) && vodClass.filters.isNotEmpty()) {
                val valid = vodClass.filters.filter { it.values.isNotEmpty() }
                if (valid.isNotEmpty()) {
                    map[vodClass.typeId] = valid
                }
            }
        }
        return map
    }

    private fun buildInitialSelectedFilters(
        filtersByClass: Map<String, List<VodFilter>>,
    ): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, Map<String, String>>()
        filtersByClass.forEach { (typeId, filters) ->
            val defaults = buildDefaultFiltersForClass(filters)
            if (defaults.isNotEmpty()) {
                result[typeId] = defaults
            }
        }
        return result
    }

    private suspend fun loadCategory(
        site: Site,
        classId: String,
        page: Int,
        filters: Map<String, String> = emptyMap(),
    ) {
        val isFirst = page == 1
        _uiState.update {
            if (isFirst) it.copy(isLoading = true, error = null)
            else it.copy(isLoadingMore = true)
        }

        val res = repository.getCategoryContent(site, classId, page, filters)

        // 请求返回时用户已切到别的站点、分类或筛选条件：丢弃过期结果，避免旧数据覆盖当前列表
        val latest = _uiState.value
        if (site.key != latest.currentSite?.key ||
            classId != latest.selectedClassId ||
            filters != latest.currentSelectedFilters
        ) {
            _uiState.update { it.copy(isLoadingMore = false) }
            return
        }

        if (res.list.isEmpty() && isFirst) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    items = emptyList(),
                    page = 1,
                    pageCount = 1,
                )
            }
        } else {
            val combined = if (isFirst) res.list else (_uiState.value.items + res.list)
            val deduplicated = combined.distinctBy { item -> "${item.siteKey.ifEmpty { site.key }}_${item.vodId}" }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    items = deduplicated,
                    page = res.page.coerceAtLeast(page),
                    pageCount = res.pageCount.coerceAtLeast(1),
                )
            }
            // 结果写入分类缓存，供下次切换回来时直接复用
            val snapshot = _uiState.value
            if (site.key == snapshot.currentSite?.key &&
                classId == snapshot.selectedClassId &&
                filters == snapshot.currentSelectedFilters
            ) {
                cacheCategory(site.key, classId, filters, snapshot.items, snapshot.page, snapshot.pageCount)
            }
        }
    }
}
