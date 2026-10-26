package com.mewbox.tv.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.bean.Site
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.ErrorState
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.VodCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDetail: (siteKey: String, vodId: String, vodName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSiteDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部操作栏
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showSiteDialog = true },
                ) {
                    Text(
                        text = state.currentSite?.name ?: "请选择站点",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "切换首页源",
                    )
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        // 分类横向 Tab 栏
        if (state.classes.isNotEmpty()) {
            val selectedIndex = state.classes.indexOfFirst { it.typeId == state.selectedClassId }.coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.classes.forEach { vodClass ->
                    Tab(
                        selected = vodClass.typeId == state.selectedClassId,
                        onClick = { viewModel.selectClass(vodClass.typeId) },
                        text = { Text(vodClass.typeName) },
                    )
                }
            }
        }

        // 内容展示区
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    LoadingState(text = "正在获取影片列表...")
                }
                state.error != null && state.items.isEmpty() -> {
                    ErrorState(message = state.error ?: "加载失败", onRetry = { viewModel.refresh() })
                }
                state.items.isEmpty() && !state.isLoading -> {
                    EmptyState(message = "当前分类下暂无影片")
                }
                else -> {
                    val gridState = rememberLazyGridState()

                    // 监听滑动到底部触发加载更多
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val layoutInfo = gridState.layoutInfo
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

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { "${it.siteKey}_${it.vodId}" }) { vod ->
                            VodCard(
                                vod = vod,
                                onClick = { onNavigateToDetail(vod.siteKey, vod.vodId, vod.vodName) },
                            )
                        }

                        if (state.isLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
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
}

@Composable
private fun SiteSelectDialog(
    currentSite: Site?,
    availableSites: List<Site>,
    onSelect: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择首页源") },
        text = {
            Column {
                availableSites.forEach { site ->
                    val isSelected = site.key == currentSite?.key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(site) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = site.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
