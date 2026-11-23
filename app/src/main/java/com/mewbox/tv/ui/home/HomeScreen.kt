package com.mewbox.tv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.ErrorState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.LocalTopBarGlassBackdrop
import com.mewbox.tv.ui.components.SubscriptionHistoryDialog
import com.mewbox.tv.ui.components.VodCard
import com.mewbox.tv.ui.components.VodGridSkeletonGroup
import com.mewbox.tv.ui.navbar.NavMetrics
import com.mewbox.tv.ui.theme.GLASS_BACKDROP_BAND_MARGIN_DP
import com.mewbox.tv.ui.theme.LiquidGlassDefaults
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.util.AppToast
import kotlinx.coroutines.launch

/**
 * 首页点播界面（沉浸悬浮玻璃双层方案）：
 * - 顶部悬浮玻璃操作区：第 1 行左侧站点选择胶囊 + 右侧圆形玻璃筛选按钮（含角标），第 2 行全宽横向分类条
 * - 海报网格全屏边到边铺设，向上滚动可穿透至顶栏毛玻璃下方，消除硬切边；支持无分类站点的推荐列表兜底渲染
 * - 3 列 2:3 海报网格（14dp 边距、10dp 圆角 + 细描边、底部渐变右下角备注、左对齐片名）
 * - 动态安全区适配底部悬浮导航与右下角回到顶部按钮
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
    var showSiteDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val tabListState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { state.classes.size })
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // 为每个分类维护独立的 LazyGridState，避免转场并发使用同一个 gridState 产生冲突，并保留返回原分类时的滚动位置
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val currentGridState = gridStates.getOrPut(state.selectedClassId) { LazyGridState() }
    val fallbackGridState = rememberLazyGridState()
    val activeGridState = if (state.classes.isNotEmpty()) currentGridState else fallbackGridState

    // 关键优化：当首页网格、横向分类条或分类 Pager 处于滑动状态时，通知外部暂停高负荷 Shader 图层渲染
    val isScrolling by remember(activeGridState, pagerState) {
        derivedStateOf {
            activeGridState.isScrollInProgress ||
                tabListState.isScrollInProgress ||
                pagerState.isScrollInProgress
        }
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
        fallbackGridState.scrollToItem(0)
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

    // 选中分类变化时同步 HorizontalPager 到对应页（点击分类 Tab 或站点切换触发）
    var lastSiteKey by remember { mutableStateOf(state.currentSite?.key) }
    LaunchedEffect(state.selectedClassId, state.classes) {
        val targetIndex = state.classes.indexOfFirst { it.typeId == state.selectedClassId }
        if (targetIndex < 0) return@LaunchedEffect
        val siteChanged = lastSiteKey != state.currentSite?.key
        lastSiteKey = state.currentSite?.key
        if (pagerState.currentPage != targetIndex) {
            isProgrammaticScroll = true
            try {
                if (siteChanged) {
                    pagerState.scrollToPage(targetIndex)
                } else {
                    pagerState.animateScrollToPage(targetIndex)
                }
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    // 横向滑动切换分类页时同步通知 ViewModel 切换当前选中分类
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                if (!isProgrammaticScroll) {
                    val classId = state.classes.getOrNull(page)?.typeId
                    if (classId != null && classId != state.selectedClassId) {
                        viewModel.selectClass(classId)
                    }
                }
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

    // 是否显示“回到顶部”悬浮按钮（关联当前活跃列表状态）
    val showBackToTop by remember(activeGridState) {
        derivedStateOf { activeGridState.firstVisibleItemIndex > 6 }
    }
    val isListScrolled by remember(activeGridState) {
        derivedStateOf {
            activeGridState.firstVisibleItemIndex > 0 || activeGridState.firstVisibleItemScrollOffset > 8
        }
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val gridBottomPadding = navBarBottom + NavMetrics.FLOATING_OVERLAY_DP.dp + 28.dp
    val fabBottomPadding = navBarBottom + NavMetrics.FLOATING_OVERLAY_DP.dp + 14.dp

    // 站点列表已加载完成但没有任何可用站源（例如全新安装/未配置订阅）
    if (state.sitesLoaded && state.availableSites.isEmpty() && !state.isNodeLoading) {
        EmptyState(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = navBarBottom + NavMetrics.FLOATING_OVERLAY_DP.dp),
            message = "暂无可用站源，请先在设置中添加",
        )
        return
    }
    if (state.isNodeLoading) {
        return
    }

    val density = LocalDensity.current
    val isDark = MewBoxTheme.isDark
    val bgColor = MaterialTheme.colorScheme.background
    val topGlassEnabled = LiquidGlassDefaults.Config.controlsEnabled && LiquidGlassDefaults.isSupported

    val fallbackHeaderHeight = statusBarTop +
        48.dp +
        (if (state.classes.isNotEmpty()) 38.dp else 0.dp)
    var expandedHeaderHeightPx by remember { mutableIntStateOf(0) }
    val expandedHeaderHeightDp = if (expandedHeaderHeightPx > 0) {
        with(density) { expandedHeaderHeightPx.toDp() }
    } else {
        fallbackHeaderHeight
    }

    var isTopBarCollapsed by remember { mutableStateOf(false) }
    val collapseThresholdPx = with(density) { 24.dp.toPx() }
    val hideTriggerDeltaPx = with(density) { 12.dp.toPx() }
    val showTriggerDeltaPx = with(density) { 8.dp.toPx() }

    LaunchedEffect(activeGridState, state.classes.isNotEmpty()) {
        isTopBarCollapsed = false
        if (state.classes.isEmpty()) return@LaunchedEffect

        var prevIndex = activeGridState.firstVisibleItemIndex
        var prevOffset = activeGridState.firstVisibleItemScrollOffset
        var downAccumPx = 0f
        var upAccumPx = 0f

        snapshotFlow {
            activeGridState.firstVisibleItemIndex to activeGridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (index == 0 && offset < collapseThresholdPx) {
                isTopBarCollapsed = false
                downAccumPx = 0f
                upAccumPx = 0f
                prevIndex = index
                prevOffset = offset
                return@collect
            }

            val deltaPx = when {
                index > prevIndex -> 100f
                index < prevIndex -> -100f
                else -> (offset - prevOffset).toFloat()
            }

            if (deltaPx > 0f) {
                downAccumPx += deltaPx
                upAccumPx = 0f
                if (downAccumPx >= hideTriggerDeltaPx) {
                    isTopBarCollapsed = true
                }
            } else if (deltaPx < 0f) {
                upAccumPx += -deltaPx
                downAccumPx = 0f
                if (upAccumPx >= showTriggerDeltaPx) {
                    isTopBarCollapsed = false
                }
            }

            prevIndex = index
            prevOffset = offset
        }
    }

    val topBackdropOnDraw: ContentDrawScope.() -> Unit = remember(bgColor) {
        { drawRect(bgColor); drawContent() }
    }
    val topGlassBackdrop = rememberLayerBackdrop(onDraw = topBackdropOnDraw)
    val topBandExtentDp = expandedHeaderHeightDp + GLASS_BACKDROP_BAND_MARGIN_DP.dp
    val topGlassBounds: (Size) -> Rect? = remember(density, topBandExtentDp) {
        { size ->
            val extentPx = with(density) { topBandExtentDp.toPx() }
            Rect(0f, 0f, size.width, extentPx.coerceAtMost(size.height))
        }
    }

    val headerGlassAlpha by animateFloatAsState(
        targetValue = if (isListScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "homeHeaderGlassAlpha",
    )
    val headerBackdropModifier = when {
        headerGlassAlpha <= 0.01f -> Modifier
        topGlassEnabled -> Modifier.drawBackdrop(
            backdrop = topGlassBackdrop,
            shape = { RectangleShape },
            effects = {
                val blurPx = with(density) { (18.dp * headerGlassAlpha).toPx() }
                if (blurPx > 0.5f) {
                    blur(blurPx)
                }
            },
            highlight = null,
            shadow = null,
            onDrawSurface = {
                val surfaceAlpha = if (isDark) 0.78f else 0.82f
                drawRect(bgColor.copy(alpha = surfaceAlpha * headerGlassAlpha))
            },
        )
        else -> Modifier.background(
            bgColor.copy(alpha = (if (isDark) 0.92f else 0.94f) * headerGlassAlpha),
        )
    }

    val onVodClick: (Vod) -> Unit = { vod ->
        if (vod.isAction) {
            viewModel.handleAction(vod)
        } else if (vod.isMsearch || vod.vodId.contains("msearch", ignoreCase = true) || state.currentSite?.indexs == true) {
            onNavigateToSearch(vod.vodName)
        } else {
            onNavigateToDetail(vod.siteKey, vod.vodId, vod.vodName, vod.vodPic)
        }
    }
    val onVodLongClick: (Vod) -> Unit = { vod ->
        if (!vod.isAction) {
            onNavigateToSearch(vod.vodName)
        }
    }

    val pullRefreshState = rememberPullToRefreshState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        // 1. 主体内容展示区（网格边到边铺设，滚动时海报向上穿透悬浮顶栏）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (topGlassEnabled) {
                        Modifier.layerBackdrop(
                            backdrop = topGlassBackdrop,
                            recordBounds = topGlassBounds,
                            pauseRecording = { pagerState.isScrollInProgress },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.pullRefresh() },
                state = pullRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = expandedHeaderHeightDp),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.classes.isNotEmpty() -> {
                        HorizontalPager(
                            state = pagerState,
                            key = { page ->
                                val id = state.classes.getOrNull(page)?.typeId
                                if (id != null) "${state.currentSite?.key}_$id" else page
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val targetClassId = state.classes.getOrNull(page)?.typeId ?: return@HorizontalPager
                            val isCurrent = targetClassId == state.selectedClassId
                            val categoryItems = if (isCurrent) {
                                state.items
                            } else {
                                displayedItemsByClass[targetClassId] ?: emptyList()
                            }
                            val gridStateForTab = gridStates.getOrPut(targetClassId) { LazyGridState() }

                            when {
                                (!isCurrent && categoryItems.isEmpty()) || (isCurrent && state.isLoading && categoryItems.isEmpty()) -> {
                                    VodGridSkeletonGroup(
                                        contentPadding = PaddingValues(
                                            start = 14.dp,
                                            end = 14.dp,
                                            top = expandedHeaderHeightDp + 6.dp,
                                            bottom = gridBottomPadding,
                                        ),
                                    )
                                }
                                isCurrent && state.error != null && categoryItems.isEmpty() -> {
                                    ErrorState(
                                        message = state.error ?: "加载失败",
                                        onRetry = { viewModel.refresh() },
                                        modifier = Modifier.padding(top = expandedHeaderHeightDp, bottom = gridBottomPadding),
                                    )
                                }
                                isCurrent && categoryItems.isEmpty() && !state.isLoading -> {
                                    EmptyState(
                                        message = "暂无数据",
                                        modifier = Modifier.padding(top = expandedHeaderHeightDp, bottom = gridBottomPadding),
                                    )
                                }
                                else -> {
                                    HomeVodGrid(
                                        items = categoryItems,
                                        gridState = gridStateForTab,
                                        headerHeightDp = expandedHeaderHeightDp,
                                        gridBottomPadding = gridBottomPadding,
                                        isCurrent = isCurrent,
                                        page = state.page,
                                        pageCount = state.pageCount,
                                        isLoadingMore = state.isLoadingMore,
                                        showEndHint = true,
                                        onLoadMore = { viewModel.loadMore() },
                                        onVodClick = onVodClick,
                                        onVodLongClick = onVodLongClick,
                                    )
                                }
                            }
                        }
                    }

                    state.items.isNotEmpty() -> {
                        // 无分类但直接在 homeContent 返回推荐列表的站点兜底渲染
                        HomeVodGrid(
                            items = state.items,
                            gridState = fallbackGridState,
                            headerHeightDp = expandedHeaderHeightDp,
                            gridBottomPadding = gridBottomPadding,
                            isCurrent = true,
                            page = state.page,
                            pageCount = state.pageCount,
                            isLoadingMore = state.isLoadingMore,
                            showEndHint = false,
                            onLoadMore = {},
                            onVodClick = onVodClick,
                            onVodLongClick = onVodLongClick,
                        )
                    }

                    state.error != null && !state.isLoading -> {
                        ErrorState(
                            message = state.error ?: "加载失败",
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.padding(top = expandedHeaderHeightDp, bottom = gridBottomPadding),
                        )
                    }

                    !state.isLoading && state.currentSite != null -> {
                        EmptyState(
                            message = "暂无数据",
                            modifier = Modifier.padding(top = expandedHeaderHeightDp, bottom = gridBottomPadding),
                        )
                    }

                    else -> {
                        LoadingState(
                            text = "",
                            modifier = Modifier.padding(top = expandedHeaderHeightDp, bottom = gridBottomPadding),
                        )
                    }
                }
            }
        }

        // 2. 顶部悬浮玻璃操作区（站源胶囊 + 圆形筛选按钮 + 全宽分类条；下滑时平滑收起第 1 行）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (!isTopBarCollapsed && size.height > 0) {
                        expandedHeaderHeightPx = size.height
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .then(headerBackdropModifier),
        ) {
            CompositionLocalProvider(
                LocalTopBarGlassBackdrop provides if (topGlassEnabled) topGlassBackdrop else null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) {
                    AnimatedVisibility(
                        visible = !isTopBarCollapsed || state.classes.isEmpty(),
                        enter = fadeIn(tween(180)) + expandVertically(
                            animationSpec = tween(220),
                            expandFrom = Alignment.Top,
                        ),
                        exit = fadeOut(tween(140)) + shrinkVertically(
                            animationSpec = tween(200),
                            shrinkTowards = Alignment.Top,
                        ),
                    ) {
                        HomeTopBar(
                            currentSiteName = state.currentSite?.name.orEmpty(),
                            isRefreshingSubscription = state.isRefreshingSubscription,
                            isSiteDialogOpen = showSiteDialog,
                            showFilterButton = currentFilters.isNotEmpty(),
                            activeFilterCount = state.activeFilterChips.size,
                            hasActiveFilters = currentSelectedFilters != state.currentDefaultFilters,
                            onSiteClick = { showSiteDialog = true },
                            onSiteLongClick = { viewModel.reloadSubscription() },
                            onLogoClick = {
                                if (state.configs.any { it.url != state.activeConfigUrl }) {
                                    showConfigDialog = true
                                } else {
                                    AppToast.show("没有可切换的订阅")
                                }
                            },
                            onFilterClick = { showFilterSheet = true },
                        )
                    }

                    if (state.classes.isNotEmpty()) {
                        HomeTabBar(
                            classes = state.classes,
                            selectedClassId = state.selectedClassId,
                            tabListState = tabListState,
                            onClassClick = { classId, isAlreadySelected ->
                                if (isAlreadySelected) {
                                    scope.launch { currentGridState.animateScrollToItem(0) }
                                } else {
                                    viewModel.selectClass(classId)
                                }
                            },
                        )
                    }
                }
            }
        }

        // 3. 右下角“回到顶部”悬浮按钮（动态避让底部导航栏与系统导航条）
        HomeBackToTopButton(
            visible = showBackToTop,
            onClick = { scope.launch { activeGridState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding),
        )
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

/** 首页 3 列影视海报网格，统一承载分类页与无分类推荐页渲染 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeVodGrid(
    items: List<Vod>,
    gridState: LazyGridState,
    headerHeightDp: Dp,
    gridBottomPadding: Dp,
    isCurrent: Boolean,
    page: Int,
    pageCount: Int,
    isLoadingMore: Boolean,
    showEndHint: Boolean,
    onLoadMore: () -> Unit,
    onVodClick: (Vod) -> Unit,
    onVodLongClick: (Vod) -> Unit,
) {
    if (isCurrent && showEndHint) {
        val shouldLoadMore by remember(gridState) {
            derivedStateOf {
                val layoutInfo = gridState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisible >= totalItems - 6
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) {
                onLoadMore()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = headerHeightDp + 6.dp,
            bottom = gridBottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = items,
            key = { vod -> if (vod.vodId.isNotEmpty()) "${vod.siteKey}_${vod.vodId}" else "${vod.siteKey}_${vod.hashCode()}" },
            contentType = { "vod" },
        ) { vod ->
            VodCard(
                vod = vod,
                onClick = { onVodClick(vod) },
                onLongClick = { onVodLongClick(vod) },
            )
        }

        if (isCurrent && showEndHint && items.isNotEmpty() && page >= pageCount && !isLoadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "end_hint", contentType = "end_hint") {
                EndHint()
            }
        }

        if (isCurrent && isLoadingMore) {
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
