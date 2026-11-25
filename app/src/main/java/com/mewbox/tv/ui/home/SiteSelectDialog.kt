package com.mewbox.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
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
 * 切换首页源底部抽屉
 * - 顶部标题行展示「切换站源」与数量概览
 * - 站源数超过 6 个时展示通栏浅灰搜索槽，支持即时过滤与键盘避让
 * - 固定双列极简卡片展示，选中态使用浅粉柔边与勾选标识，打开抽屉时自动定位当前源
 */
@Composable
fun SiteSelectDialog(
    currentSite: Site?,
    availableSites: List<Site>,
    onSelect: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }

    val filteredSites = remember(keyword, availableSites) {
        val query = keyword.trim().lowercase()
        if (query.isEmpty()) {
            availableSites
        } else {
            availableSites.filter {
                it.name.lowercase().contains(query) || it.key.lowercase().contains(query)
            }
        }
    }

    val gridState = rememberLazyGridState()
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp.coerceIn(240.dp, 460.dp)

    LaunchedEffect(Unit) {
        val currentKey = currentSite?.key ?: return@LaunchedEffect
        val selectedIndex = availableSites.indexOfFirst { it.key == currentKey }
        if (selectedIndex >= 0) {
            gridState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
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
            SiteSheetHeader(
                totalCount = availableSites.size,
                filteredCount = filteredSites.size,
                isFiltering = keyword.isNotBlank(),
            )

            if (availableSites.size > 6) {
                Spacer(Modifier.height(12.dp))
                SiteSearchBar(
                    keyword = keyword,
                    onKeywordChange = { keyword = it },
                )
            }

            Spacer(Modifier.height(14.dp))

            if (filteredSites.isEmpty()) {
                SiteEmptyState(keyword = keyword)
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
                        SiteDialogItem(
                            site = site,
                            isSelected = site.key == currentSite?.key,
                            onClick = { onSelect(site) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteSheetHeader(
    totalCount: Int,
    filteredCount: Int,
    isFiltering: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "切换站源",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = OverlayTokens.DialogTitle,
        )
        Text(
            text = if (isFiltering) "命中 $filteredCount 个" else "共 $totalCount 个",
            fontSize = 12.5.sp,
            color = OverlayTokens.TextSecondary,
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

@Composable
private fun SiteSearchBar(
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
        label = "siteSearchBorder",
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
            imageVector = Icons.Rounded.Search,
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
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清除",
                    tint = OverlayTokens.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SiteEmptyState(keyword: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (keyword.isBlank()) Icons.Rounded.Inbox else Icons.Rounded.SearchOff,
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
private fun SiteDialogItem(
    site: Site,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
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
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = site.name,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else OverlayTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "当前站源",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
