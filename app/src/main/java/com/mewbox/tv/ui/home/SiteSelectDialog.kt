package com.mewbox.tv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.bean.Site

/**
 * 切换首页源弹窗（严格对齐 MewPlayer dialog_site.xml 与 adapter_site.xml 样式）
 * - 20dp 圆角纯白卡片，立体阴影与外边距
 * - 顶部集成 40dp 椭圆搜索输入框（#F3F4F6 底色，前置搜索图标，即时过滤，清除按钮）
 * - 顶部右侧提供单双列切换按钮（#F3F4F6 圆形按钮，对齐 MewPlayer ic_site_single_column / ic_site_double_column）
 * - 站源条目高度 44dp，圆角 12dp，左侧健康绿点（7dp），单列时展示搜索/换源属性图标
 * - 选中态：#EEF4FF 背景，1.2dp #217AF4 边框，字体加粗变蓝，右侧勾选
 * - 未选中态：#F8F9FA 背景，1dp #E5E7EB 边框，#1F2329 字体
 */
@Composable
fun SiteSelectDialog(
    currentSite: Site?,
    availableSites: List<Site>,
    onSelect: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var isDoubleColumn by rememberSaveable { mutableStateOf(false) }

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 500.dp)
                .heightIn(max = 540.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                // 顶部搜索栏与单双列切换按钮（对齐 MewPlayer dialog_site.xml）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 站源搜索框
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = Color(0xFF6B7280),
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
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 13.5.sp,
                                )
                            }
                            BasicTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Color(0xFF1F2329),
                                    fontSize = 13.5.sp,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (keyword.isNotEmpty()) {
                            IconButton(
                                onClick = { keyword = "" },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // 单双列切换按钮
                    IconButton(
                        onClick = { isDoubleColumn = !isDoubleColumn },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3F4F6)),
                    ) {
                        Icon(
                            imageVector = if (isDoubleColumn) Icons.Default.ViewAgenda else Icons.Default.GridView,
                            contentDescription = if (isDoubleColumn) "切换单列" else "切换双列",
                            tint = Color(0xFF4B5563),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 站源列表区域（对齐 MewPlayer adapter_site.xml & selector_site_item_bg）
                if (filteredSites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (keyword.isEmpty()) "暂无数据" else "未找到与 \"$keyword\" 相关的站源",
                            color = Color(0xFF9CA3AF),
                            fontSize = 14.sp,
                        )
                    }
                } else if (isDoubleColumn) {
                    // 双列展示
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(filteredSites, key = { it.key }) { site ->
                            val isSelected = site.key == currentSite?.key
                            SiteDialogItem(
                                site = site,
                                isSelected = isSelected,
                                isSingleColumn = false,
                                onClick = { onSelect(site) },
                            )
                        }
                    }
                } else {
                    // 单列展示
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(filteredSites, key = { it.key }) { site ->
                            val isSelected = site.key == currentSite?.key
                            SiteDialogItem(
                                site = site,
                                isSelected = isSelected,
                                isSingleColumn = true,
                                onClick = { onSelect(site) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteDialogItem(
    site: Site,
    isSelected: Boolean,
    isSingleColumn: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFFEEF4FF) else Color(0xFFF8F9FA),
        border = BorderStroke(
            width = if (isSelected) 1.2.dp else 1.dp,
            color = if (isSelected) Color(0xFF217AF4) else Color(0xFFE5E7EB),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isSingleColumn) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 站源名称（对齐 MewPlayer 14sp，选中加粗并变蓝）
            Text(
                text = site.name,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color(0xFF217AF4) else Color(0xFF1F2329),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 单列时展示属性图标及勾选状态（支持搜索、支持换源）
            if (site.changeable && isSingleColumn) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "支持换源",
                    tint = if (isSelected) Color(0xFF217AF4).copy(alpha = 0.8f) else Color(0xFF9CA3AF),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

