package com.mewbox.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Config
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.bean.VodClass
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
)

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

    private fun cacheKey(siteKey: String, classId: String): String = "$siteKey|$classId"

    private fun cacheCategory(siteKey: String, classId: String, items: List<Vod>, page: Int, pageCount: Int) {
        if (siteKey.isEmpty() || classId.isEmpty() || items.isEmpty()) return
        categoryCache[cacheKey(siteKey, classId)] = CategoryCache(items, page, pageCount)
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

        // 2. 监听当前首页站点、配置重载事件与 Node.js 运行时就绪状态，驱动首页内容加载
        viewModelScope.launch {
            combine(
                repository.observeHomeSite(),
                vodConfig.configReloadFlow,
                nodeRuntime.loading,
            ) { homeSite, reloadTime, nodeLoading ->
                Triple(homeSite, reloadTime, nodeLoading)
            }.collectLatest { (homeSite, _, nodeLoading) ->
                if (homeSite != null) {
                    // 如果 Node.js 运行时正在下载或启动中，等待其就绪（变为 null）后再加载首页内容，避免必败的无效请求
                    if (nodeLoading != null) {
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

        // 命中缓存：直接复用已加载过的列表，不再发起请求
        val cached = categoryCache[cacheKey(site.key, classId)]
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
            loadCategory(site, classId, page = 1)
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
                val stillValid = classes.any { it.typeId == _uiState.value.selectedClassId }
                val targetClassId = if (stillValid) {
                    _uiState.value.selectedClassId
                } else {
                    classes.firstOrNull()?.typeId.orEmpty()
                }
                _uiState.update { it.copy(classes = classes, selectedClassId = targetClassId) }

                // 分类列表变化后，清掉**当前站点**下已被删除分类的缓存（其他站点的缓存不动）
                if (home.classes.isNotEmpty()) {
                    val sitePrefix = "${site.key}|"
                    val validKeys = home.classes.map { cacheKey(site.key, it.typeId) }.toSet()
                    categoryCache.keys.retainAll { key ->
                        !key.startsWith(sitePrefix) || key in validKeys
                    }
                }

                // 2. 刷新当前分类第一页
                if (targetClassId.isNotEmpty()) {
                    val res = repository.getCategoryContent(site, targetClassId, 1)
                    if (res.list.isEmpty()) {
                        // 拉取失败或确实为空：保留原有列表，仅提示，避免刷成一片空白
                        _uiState.update { it.copy(message = "刷新失败，请检查网络或站点") }
                    } else {
                        _uiState.update {
                            it.copy(
                                items = res.list,
                                page = res.page.coerceAtLeast(1),
                                pageCount = res.pageCount.coerceAtLeast(1),
                                error = null,
                            )
                        }
                        // 刷新结果同步进缓存，切走再切回时看到的是最新数据
                        cacheCategory(
                            site.key,
                            targetClassId,
                            res.list,
                            res.page.coerceAtLeast(1),
                            res.pageCount.coerceAtLeast(1),
                        )
                    }
                } else if (home.list.isNotEmpty()) {
                    // 无分类的站点，直接用 homeContent 返回的推荐列表
                    _uiState.update {
                        it.copy(items = home.list, page = 1, pageCount = 1)
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
                _uiState.update { it.copy(message = "订阅切换成功（共 $count 个站点）") }
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
                _uiState.update {
                    it.copy(
                        isRefreshingSubscription = false,
                        message = "订阅刷新成功（共 $count 个站点）",
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
                _uiState.value.currentSite?.let { site ->
                    loadHome(site)
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

        viewModelScope.launch {
            loadCategory(site, classId, page = current.page + 1)
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

            _uiState.update {
                it.copy(
                    classes = classes,
                    selectedClassId = firstClassId,
                    isLoading = firstClassId.isNotEmpty(),
                )
            }

            if (firstClassId.isNotEmpty()) {
                loadCategory(site, firstClassId, page = 1)
            } else if (res.list.isNotEmpty()) {
                // 部分站点直接在 homeContent 返回推荐视频
                _uiState.update {
                    it.copy(
                        items = res.list,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadCategory(site: Site, classId: String, page: Int) {
        val isFirst = page == 1
        _uiState.update {
            if (isFirst) it.copy(isLoading = true, error = null)
            else it.copy(isLoadingMore = true)
        }

        val res = repository.getCategoryContent(site, classId, page)

        // 请求返回时用户已切到别的分类：丢弃过期结果，避免旧分类数据覆盖当前分类
        if (classId != _uiState.value.selectedClassId) {
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
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    items = if (isFirst) res.list else (it.items + res.list).distinctBy { item -> "${item.siteKey}_${item.vodId}" },
                    page = res.page.coerceAtLeast(page),
                    pageCount = res.pageCount.coerceAtLeast(1),
                )
            }
            // 结果写入分类缓存，供下次切换回来时直接复用
            if (classId == _uiState.value.selectedClassId && site.key == _uiState.value.currentSite?.key) {
                val snapshot = _uiState.value
                cacheCategory(site.key, classId, snapshot.items, snapshot.page, snapshot.pageCount)
            }
        }
    }
}
