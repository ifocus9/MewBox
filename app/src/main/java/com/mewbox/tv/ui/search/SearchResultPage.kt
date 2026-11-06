package com.mewbox.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.VodCard
import com.mewbox.tv.ui.components.VodGridSkeletonGroup
import com.mewbox.tv.ui.components.VodListSkeletonGroup
import com.mewbox.tv.ui.components.glassTopBarSurface

/**
 * 搜索结果聚合页（严格对齐 MewPlayer CollectFragment 视觉与交互）
 */
@Composable
fun SearchResultPage(
    state: SearchUiState,
    onBackToInput: () -> Unit,
    onToggleColumn: () -> Unit,
    onSelectSite: (String?) -> Unit,
    onVodClick: (Vod) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 计算左侧站点聚合侧栏条目
    val sidebarItems = remember(state.results) {
        val totalCount = state.results.size
        val allItem = SiteResultItem(siteKey = null, siteName = "全部", count = totalCount)
        val siteGroups = state.results.groupBy { it.siteKey }
        val siteItems = siteGroups.map { (key, list) ->
            val siteName = list.firstOrNull()?.siteName?.ifBlank { key } ?: key
            SiteResultItem(siteKey = key, siteName = siteName, count = list.size)
        }
        listOf(allItem) + siteItems
    }

    // 过滤右侧展示的视频列表
    val displayedVods = remember(state.results, state.selectedSiteKey) {
        if (state.selectedSiteKey == null) {
            state.results
        } else {
            state.results.filter { it.siteKey == state.selectedSiteKey }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部 Toolbar（点击标题直接返回搜索输入框重新编辑）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassTopBarSurface(CircleShape, Color(0xFFF4F5F7))
                        .clickable(onClick = onBackToInput),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回搜索",
                        tint = Color(0xFF1F2329),
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 搜索输入胶囊（点击返回搜索输入框重新编辑，样式对齐 SearchInputPage）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .glassTopBarSurface(
                            shape = ContinuousCapsule,
                            fallbackColor = Color(0xFFF4F5F7),
                            pressEffect = false,
                        )
                        .clickable(onClick = onBackToInput)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "重新编辑",
                        tint = Color(0xFF8A8F99),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.keyword.ifEmpty { "搜索影片、演员、剧集..." },
                            color = if (state.keyword.isEmpty()) Color(0xFF8A8F99) else Color(0xFF1F2329),
                            fontSize = 14.5.sp,
                            fontWeight = if (state.keyword.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
            }

            // 单双列切换按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, Color(0xFFF4F5F7))
                .clickable(onClick = onToggleColumn),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isDoubleColumn) Icons.Default.ViewAgenda else Icons.Default.GridView,
                    contentDescription = "切换单双列",
                    tint = Color(0xFF1F2329),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 主体双栏布局：左右各一块白色圆角面板，灰底缝隙即分区（以面代线，替代竖向分割线）
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧站点侧边栏：整体白色圆角面板 + 胶囊条目（宽度 110dp，对齐 MewPlayer adapter_collect）
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                modifier = Modifier
                    .width(110.dp)
                    .fillMaxHeight()
                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(sidebarItems, key = { it.siteKey ?: "all" }) { item ->
                        val isSelected = (item.siteKey == null && state.selectedSiteKey == null) ||
                            (item.siteKey != null && item.siteKey == state.selectedSiteKey)

                        CollectSidebarItem(
                            name = item.siteName,
                            count = item.count,
                            isSelected = isSelected,
                            onClick = { onSelectSite(item.siteKey) },
                        )
                    }
                }
            }

            // 栏间留白：中间露出页面灰底，天然形成视觉分区
            Spacer(modifier = Modifier.width(8.dp))

            // 右侧搜索结果展示区：白色圆角面板
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                // 搜索进度指示器
                if (state.isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = Color(0xFF217AF4),
                        trackColor = Color(0xFFE8F2FF),
                    )
                }
                when {
                    displayedVods.isEmpty() && state.isSearching -> {
                        if (state.isDoubleColumn) {
                            VodGridSkeletonGroup(
                                columns = 2,
                                contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                            )
                        } else {
                            VodListSkeletonGroup(
                                contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                            )
                        }
                    }
                    displayedVods.isEmpty() && !state.isSearching -> {
                        EmptyState(message = "未找到与 \"${state.keyword}\" 相关的结果")
                    }
                    state.isDoubleColumn -> {
                        // 双列网格模式
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(displayedVods, key = { "${it.siteKey}_${it.vodId}" }) { vod ->
                                VodCard(
                                    vod = vod,
                                    showSite = true,
                                    onClick = { onVodClick(vod) },
                                )
                            }
                        }
                    }
                    else -> {
                        // 单列卡片模式（严格对齐 MewPlayer adapter_search.xml 横向图文卡片）
                        LazyColumn(
                            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(displayedVods, key = { "${it.siteKey}_${it.vodId}" }) { vod ->
                                SearchResultCard(
                                    vod = vod,
                                    onClick = { onVodClick(vod) },
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * 聚合站点侧栏条目（胶囊样式：未选中浅灰蓝底深色字，选中主题蓝实心底 + 白字，ContinuousCapsule 连续圆角）
 */
@Composable
private fun CollectSidebarItem(
    name: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ContinuousCapsule,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF0F4FA),
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$name $count",
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else Color(0xFF333842),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
