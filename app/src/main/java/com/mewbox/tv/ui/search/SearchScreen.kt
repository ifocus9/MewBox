package com.mewbox.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 搜索主界面（协调层：高内聚低耦合）
 * - 管理全局状态（SearchViewModel 与 SearchUiState）
 * - 拦截系统返回键逻辑
 * - 调度搜索输入界面 (SearchInputPage) 与搜索结果聚合界面 (SearchResultPage)
 * - 调度指定搜索站点弹窗 (SearchSiteFilterDialog)
 */
@Composable
fun SearchScreen(
    onNavigateToDetail: (siteKey: String, vodId: String, vodName: String, vodPic: String) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSiteFilterDialog by remember { mutableStateOf(false) }

    // 当处于搜索结果页时，拦截返回键返回搜索输入页；在搜索输入页返回则退回上层界面
    BackHandler(enabled = state.hasSearched) {
        viewModel.exitSearchResult()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!state.hasSearched) {
            // ==================== 1. 搜索输入页 (MewPlayer SearchFragment) ====================
            SearchInputPage(
                state = state,
                onBack = onBack,
                onKeywordChange = viewModel::onKeywordChange,
                onSearch = { viewModel.search() },
                onWordClick = { word -> viewModel.search(word) },
                onClearHistory = viewModel::clearHistory,
                onDeleteHistory = viewModel::deleteHistoryItem,
                onOpenSiteFilter = { showSiteFilterDialog = true },
            )
        } else {
            // ==================== 2. 搜索结果聚合页 (MewPlayer CollectFragment) ====================
            SearchResultPage(
                state = state,
                onBackToInput = { viewModel.exitSearchResult() },
                onToggleColumn = { viewModel.toggleColumn() },
                onSelectSite = { siteKey -> viewModel.selectSite(siteKey) },
                onVodClick = { vod ->
                    if (vod.isAction) {
                        viewModel.handleAction(vod)
                    } else {
                        onNavigateToDetail(vod.siteKey, vod.vodId, vod.vodName, vod.vodPic)
                    }
                },
            )
        }
    }

    // 指定搜索站点弹窗
    if (showSiteFilterDialog) {
        SearchSiteFilterDialog(
            currentSiteKey = state.searchTargetSiteKey,
            sites = state.availableSites,
            onSelect = { siteKey ->
                viewModel.setSearchTargetSite(siteKey)
                showSiteFilterDialog = false
            },
            onToggleSearchable = viewModel::toggleSiteSearchable,
            onDismiss = { showSiteFilterDialog = false },
        )
    }
}
