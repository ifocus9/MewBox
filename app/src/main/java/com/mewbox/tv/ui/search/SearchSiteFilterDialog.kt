package com.mewbox.tv.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.bean.Site

/**
 * 搜索指定站点筛选对话框
 * - 默认按源配置的 searchable 展示是否支持搜索
 * - 在可搜索源中，支持单点切换开启/禁用（即时持久化到数据库）
 * - 支持源名称快速过滤搜索
 * - 点击站点主体行直接设为单站搜索目标并关闭弹窗
 */
@Composable
fun SearchSiteFilterDialog(
    currentSiteKey: String?,
    sites: List<Site>,
    onSelect: (String?) -> Unit,
    onToggleSearchable: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    var filterQuery by remember { mutableStateOf("") }
    val filteredSites = remember(sites, filterQuery) {
        if (filterQuery.isBlank()) sites
        else sites.filter { it.name.contains(filterQuery.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                // 站点筛选输入框（当站点较多时提供即时过滤）
                if (sites.size > 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFF4F5F7))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF8A8F99),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (filterQuery.isEmpty()) {
                                Text(
                                    text = "输入源名称过滤...",
                                    color = Color(0xFF8A8F99),
                                    fontSize = 13.sp,
                                )
                            }
                            BasicTextField(
                                value = filterQuery,
                                onValueChange = { filterQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Color(0xFF1F2329),
                                    fontSize = 13.sp,
                                ),
                                cursorBrush = SolidColor(Color(0xFF217AF4)),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (filterQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { filterQuery = "" },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除",
                                    tint = Color(0xFF8A8F99),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 站点列表
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    // 单站点条目
                    items(filteredSites, key = { it.key }) { site ->
                        val isSelected = site.key == currentSiteKey
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) Color(0xFFEEF4FF) else Color(0xFFF8F9FA),
                            border = BorderStroke(
                                width = if (isSelected) 1.2.dp else 1.dp,
                                color = if (isSelected) Color(0xFF217AF4) else Color(0xFFE5E7EB),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 12.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 左侧主体区域：点击指定为单站搜索目标并关闭弹窗
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { onSelect(site.key) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = site.name,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF217AF4) else Color(0xFF1F2329),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF217AF4),
                                            modifier = Modifier
                                                .padding(horizontal = 6.dp)
                                                .size(18.dp),
                                        )
                                    }
                                }

                                // 右侧搜索开关图标按钮（仅可搜索的源支持切换；源不支持则显示置灰图标）
                                if (site.isSearchSupport) {
                                    IconButton(
                                        onClick = { onToggleSearchable(site) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (site.isSearchable) Icons.Default.Search else Icons.Default.Block,
                                            contentDescription = if (site.isSearchable) "禁用此源搜索" else "启用此源搜索",
                                            tint = Color(0xFF8A8F99),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.size(36.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = "源不支持搜索",
                                            tint = Color(0xFF8A8F99),
                                            modifier = Modifier.size(20.dp),
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
}
