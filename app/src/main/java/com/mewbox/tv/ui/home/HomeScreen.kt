package com.mewbox.tv.ui.home

import com.mewbox.tv.util.AppToast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.ErrorState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.SubscriptionHistoryDialog
import com.mewbox.tv.ui.components.VodCard
import com.mewbox.tv.ui.components.VodGridSkeletonGroup
import com.mewbox.tv.ui.navbar.NavMetrics
import kotlinx.coroutines.launch

/**
 * 首页点播界面（严格对齐 MewPlayer fragment_vod.xml 风格与截图细节）
 * - 顶部 Toolbar：左侧站点选择胶囊（带圆角/阴影/Logo/箭头）
 * - 分类导航条：横向滑动，选中文字高亮蓝色 + 下方蓝色滑条指示器，右侧 4 个小方块分类菜单按钮
 * - 视频网格：3 列瀑布流网格，卡片右上角橙色备注标签，片名居中单行排布
 * - 浮动回到顶部按钮 (FAB)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToDetail: (siteKey: String, vodId: String, vodName: String, vodPic: String) -> Unit,
    onNavigateToSearch: (keyword: String) -> Unit = {},
    modifier: Modifier = Modifier,
    onScrollInProgressChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSiteDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val tabListState = rememberLazyListState()

    // 为每个分类维护独立的 LazyGridState，避免转场并发使用同一个 gridState 产生冲突，并保留返回原分类时的滚动位置
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val currentGridState = gridStates.getOrPut(state.selectedClassId) { LazyGridState() }

    // 关键优化：当首页网格或横向分类条处于滑动状态时，通知外部暂停高负荷 Shader 图层渲染
    val isScrolling by remember(currentGridState) {
        derivedStateOf { currentGridState.isScrollInProgress || tabListState.isScrollInProgress }
    }
    LaunchedEffect(isScrolling) {
        onScrollInProgressChange?.invoke(isScrolling)
    }

    // 记忆每个分类最近展示的影片列表，确保在 Tab 转场退场动画的短暂时间里，旧页面不会突变成新分类的数据
    val displayedItemsByClass = remember { mutableStateMapOf<String, List<Vod>>() }
    val appliedFiltersSnapshot = remember { mutableMapOf<String, Map<String, String>>() }

    // 站点切换时清空历史分类快照、筛选快照和各分类的滚动状态
    LaunchedEffect(state.currentSite?.key) {
        displayedItemsByClass.clear()
        gridStates.clear()
        appliedFiltersSnapshot.clear()
        showFilterSheet = false
    }

    // 筛选条件变化时将当前分类列表回到顶部，避免旧滚动位置引起列表偏移或误分页
    val currentFilters = state.currentFilters
    val currentSelectedFilters = state.currentSelectedFilters
    LaunchedEffect(state.selectedClassId, currentSelectedFilters) {
        val classId = state.selectedClassId
        if (classId.isEmpty()) return@LaunchedEffect
        val previous = appliedFiltersSnapshot[classId]
        if (previous != null && previous != currentSelectedFilters) {
            gridStates[classId]?.scrollToItem(0)
        }
        appliedFiltersSnapshot[classId] = currentSelectedFilters
    }

    // 及时缓存当前分类的影片数据，供分类切换退场动画展示，避免旧页面在退场时突变为新分类数据
    LaunchedEffect(state.selectedClassId, state.items) {
        if (state.selectedClassId.isNotEmpty() && state.items.isNotEmpty()) {
            displayedItemsByClass[state.selectedClassId] = state.items
        }
    }

    // 订阅刷新等提示反馈
    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            AppToast.show(msg)
            viewModel.consumeMessage()
        }
    }

    // 选中分类时横向 Tab 仅在选中项完全不可见时才平滑滚动
    // （animateScrollToItem 会把目标滚到最左，点击可见分类时不该有这个跳动）
    LaunchedEffect(state.selectedClassId) {
        val index = state.classes.indexOfFirst { it.typeId == state.selectedClassId }
        if (index >= 0) {
            val itemInfo = tabListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
            val fullyVisible = itemInfo != null &&
                itemInfo.offset >= tabListState.layoutInfo.viewportStartOffset &&
                itemInfo.offset + itemInfo.size <= tabListState.layoutInfo.viewportEndOffset
            if (!fullyVisible) {
                tabListState.animateScrollToItem(index)
            }
        }
    }

    // 是否显示“回到顶部”悬浮按钮（关联当前分类的 LazyGridState，分类切换时重新订阅）
    val showBackToTop by remember(currentGridState) {
        derivedStateOf { currentGridState.firstVisibleItemIndex > 6 }
    }

    // 站点列表已加载完成但没有任何可用站源（例如全新安装/未配置订阅）
    if (state.sitesLoaded && state.availableSites.isEmpty() && !state.isNodeLoading) {
        EmptyState(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding(),
            message = "暂无可用站源，请先在设置中添加",
        )
        return
    }
    if (state.isNodeLoading) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 1. 顶部操作栏
        HomeTopBar(
            currentSiteName = state.currentSite?.name.orEmpty(),
            isRefreshingSubscription = state.isRefreshingSubscription,
            onSiteClick = { showSiteDialog = true },
            onSiteLongClick = { viewModel.reloadSubscription() },
            onLogoClick = {
                if (state.configs.any { it.url != state.activeConfigUrl }) {
                    showConfigDialog = true
                } else {
                    AppToast.show("没有可切换的订阅")
                }
            },
        )

        if (state.classes.isNotEmpty()) {
            // 2. 分类横向导航条
            HomeTabBar(
                classes = state.classes,
                selectedClassId = state.selectedClassId,
                tabListState = tabListState,
                showFilterButton = currentFilters.isNotEmpty(),
                hasActiveFilters = currentSelectedFilters != state.currentDefaultFilters,
                onClassClick = { classId, isAlreadySelected ->
                    if (isAlreadySelected) {
                        scope.launch { currentGridState.animateScrollToItem(0) }
                    } else {
                        viewModel.selectClass(classId)
                    }
                },
                onFilterClick = { showFilterSheet = true },
            )

            // 3. 当前分类下的主体内容展示区与网格（支持下拉刷新与平滑分类切换动画）
            Box(modifier = Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.pullRefresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AnimatedContent(
                        targetState = state.selectedClassId,
                        transitionSpec = {
                            fadeIn(
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                            ) togetherWith fadeOut(
                                animationSpec = tween(120, easing = FastOutSlowInEasing),
                            )
                        },
                        label = "HomeVodGridTransition",
                        modifier = Modifier.fillMaxSize(),
                    ) { targetClassId ->
                        val isCurrent = targetClassId == state.selectedClassId
                        val categoryItems = if (isCurrent) {
                            state.items
                        } else {
                            displayedItemsByClass[targetClassId] ?: emptyList()
                        }
                        val gridStateForTab = gridStates.getOrPut(targetClassId) { LazyGridState() }

                        when {
                            isCurrent && state.isLoading && categoryItems.isEmpty() -> {
                                VodGridSkeletonGroup()
                            }
                            isCurrent && state.error != null && categoryItems.isEmpty() -> {
                                ErrorState(
                                    message = state.error ?: "加载失败",
                                    onRetry = { viewModel.refresh() },
                                )
                            }
                            categoryItems.isEmpty() && (!isCurrent || !state.isLoading) -> {
                                EmptyState(message = "暂无数据")
                            }
                            else -> {
                                if (isCurrent) {
                                    val shouldLoadMore by remember(gridStateForTab) {
                                        derivedStateOf {
                                            val layoutInfo = gridStateForTab.layoutInfo
                                            val totalItems = layoutInfo.totalItemsCount
                                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                            totalItems > 0 && lastVisible >= totalItems - 6
                                        }
                                    }

                                    LaunchedEffect(shouldLoadMore) {
                                        if (shouldLoadMore) {
                                            viewModel.loadMore()
                                        }
                                    }
                                }

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    state = gridStateForTab,
                                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 150.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(
                                        items = categoryItems,
                                        key = { vod -> if (vod.vodId.isNotEmpty()) "${vod.siteKey}_${vod.vodId}" else "${vod.siteKey}_${vod.hashCode()}" },
                                        contentType = { "vod" },
                                    ) { vod ->
                                        VodCard(
                                            vod = vod,
                                            onClick = {
                                                if (vod.isAction) {
                                                    viewModel.handleAction(vod)
                                                } else if (vod.isMsearch || vod.vodId.contains("msearch", ignoreCase = true) || state.currentSite?.indexs == true) {
                                                    onNavigateToSearch(vod.vodName)
                                                } else {
                                                    onNavigateToDetail(vod.siteKey, vod.vodId, vod.vodName, vod.vodPic)
                                                }
                                            },
                                            onLongClick = {
                                                if (!vod.isAction) {
                                                    onNavigateToSearch(vod.vodName)
                                                }
                                            },
                                        )
                                    }

                                    if (isCurrent && categoryItems.isNotEmpty() && state.page >= state.pageCount && !state.isLoadingMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "end_hint", contentType = "end_hint") {
                                            EndHint()
                                        }
                                    }

                                    if (isCurrent && state.isLoadingMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "loading_more", contentType = "loading_more") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                ContainedLoadingIndicator(
                                                    modifier = Modifier.size(32.dp),
                                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 右下角“回到顶部”悬浮按钮
                HomeBackToTopButton(
                    visible = showBackToTop,
                    onClick = { scope.launch { currentGridState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 106.dp),
                )
            }
        } else {
            // 还没有站源分类：站点/订阅尚未就绪，展示通用加载或失败重试
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = NavMetrics.FLOATING_OVERLAY_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.pullRefresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.error != null && !state.isLoading) {
                        ErrorState(
                            message = state.error ?: "加载失败",
                            onRetry = { viewModel.refresh() },
                        )
                    } else {
                        LoadingState(text = "")
                    }
                }
            }
        }
    }

    // 快捷切换站点弹窗
    if (showSiteDialog) {
        SiteSelectDialog(
            currentSite = state.currentSite,
            availableSites = state.availableSites,
            onSelect = { site ->
                viewModel.switchHomeSite(site)
                showSiteDialog = false
            },
            onDismiss = { showSiteDialog = false },
        )
    }

    // 订阅切换弹窗
    if (showConfigDialog) {
        SubscriptionHistoryDialog(
            configs = state.configs.filter { it.url != state.activeConfigUrl },
            onSelect = { url ->
                showConfigDialog = false
                viewModel.switchSubscription(url)
            },
            onDismiss = { showConfigDialog = false },
        )
    }

    // 筛选抽屉弹窗
    if (showFilterSheet && currentFilters.isNotEmpty()) {
        TypeSheetDialog(
            filters = currentFilters,
            selectedFilters = currentSelectedFilters,
            showReset = currentSelectedFilters != state.currentDefaultFilters,
            onSelectFilter = { filterKey, value ->
                viewModel.selectFilter(filterKey, value)
            },
            onReset = {
                viewModel.resetFilters()
            },
            onDismiss = { showFilterSheet = false },
        )
    }
}
