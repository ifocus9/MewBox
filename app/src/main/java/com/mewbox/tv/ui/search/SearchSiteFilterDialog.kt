package com.mewbox.tv.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Site
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 搜索指定站点筛选底部抽屉
 * - 顶部标题行展示「搜索站源」、已启用数量概览，以及选中单站时的「重置」操作
 * - 站点数超过 6 个时展示通栏浅灰搜索槽，支持按名称或 key 即时过滤与键盘避让
 * - 固定双列极简卡片展示，选中态使用浅粉柔边与勾选标识，右侧保留搜索启用开关
 */
@Composable
fun SearchSiteFilterDialog(
    currentSiteKey: String?,
    sites: List<Site>,
    onSelect: (String?) -> Unit,
    onToggleSearchable: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val filteredSites = remember(sites, filterQuery) {
        val query = filterQuery.trim().lowercase()
        if (query.isEmpty()) {
            sites
        } else {
            sites.filter {
                it.name.lowercase().contains(query) || it.key.lowercase().contains(query)
            }
        }
    }
    val searchableCount = remember(sites) { sites.count { it.isSearchable } }

    val gridState = rememberLazyGridState()
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp.coerceIn(240.dp, 460.dp)

    LaunchedEffect(Unit) {
        if (currentSiteKey != null) {
            val selectedIndex = sites.indexOfFirst { it.key == currentSiteKey }
            if (selectedIndex >= 0) {
                gridState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
            }
        }
    }

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SearchSiteSheetHeader(
                totalCount = sites.size,
                searchableCount = searchableCount,
                filteredCount = filteredSites.size,
                isFiltering = filterQuery.isNotBlank(),
                hasTargetSite = currentSiteKey != null,
                onResetTarget = { onSelect(null) },
            )

            if (sites.size > 6) {
                Spacer(Modifier.height(12.dp))
                SearchSiteFilterSearchBar(
                    keyword = filterQuery,
                    onKeywordChange = { filterQuery = it },
                )
            }

            Spacer(Modifier.height(14.dp))

            if (filteredSites.isEmpty()) {
                SearchSiteEmptyState(keyword = filterQuery)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                ) {
                    items(filteredSites, key = { it.key }) { site ->
                        val isSelected = site.key == currentSiteKey
                        SearchSiteFilterItem(
                            site = site,
                            isSelected = isSelected,
                            onSelect = { onSelect(if (isSelected) null else site.key) },
                            onToggleSearchable = { onToggleSearchable(site) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSiteSheetHeader(
    totalCount: Int,
    searchableCount: Int,
    filteredCount: Int,
    isFiltering: Boolean,
    hasTargetSite: Boolean,
    onResetTarget: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "搜索站源",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OverlayTokens.DialogTitle,
            )
            Text(
                text = if (isFiltering) "命中 $filteredCount 个" else "已启 $searchableCount / $totalCount",
                fontSize = 12.5.sp,
                color = OverlayTokens.TextSecondary,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }

        if (hasTargetSite) {
            Text(
                text = "重置",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onResetTarget)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SearchSiteFilterSearchBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) primaryColor.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "searchSiteFilterBorder",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OverlayTokens.SheetItemBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "搜索",
            tint = OverlayTokens.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (keyword.isEmpty()) {
                Text(
                    text = "搜索站源...",
                    color = OverlayTokens.TextSecondary,
                    fontSize = 13.5.sp,
                )
            }
            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = OverlayTokens.TextPrimary,
                    fontSize = 13.5.sp,
                ),
                cursorBrush = SolidColor(primaryColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            )
        }
        if (keyword.isNotEmpty()) {
            IconButton(
                onClick = { onKeywordChange("") },
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "清除",
                    tint = OverlayTokens.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchSiteEmptyState(keyword: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = OverlayTokens.TextSecondary.copy(alpha = 0.45f),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (keyword.isBlank()) "暂无可用站源" else "未找到与「${keyword.trim()}」相关的站源",
            color = OverlayTokens.TextSecondary,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchSiteFilterItem(
    site: Site,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleSearchable: () -> Unit,
) {
    val titleColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        site.isSearchable -> OverlayTokens.TextPrimary
        else -> OverlayTokens.TextSecondary
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else OverlayTokens.SheetItemBg,
        border = if (isSelected) BorderStroke(0.8.dp, MewBoxTheme.colors.primaryBorder) else null,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 11.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onSelect),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = site.name,
                    fontSize = 13.5.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已指定搜索站源",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(2.dp))

            if (site.isSearchSupport) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleSearchable),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (site.isSearchable) Icons.Default.Search else Icons.Default.Block,
                        contentDescription = if (site.isSearchable) "禁用此源搜索" else "启用此源搜索",
                        tint = when {
                            site.isSearchable && isSelected -> MaterialTheme.colorScheme.primary
                            site.isSearchable -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> OverlayTokens.TextSecondary.copy(alpha = 0.55f)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "源不支持搜索",
                        tint = OverlayTokens.TextSecondary.copy(alpha = 0.35f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}
