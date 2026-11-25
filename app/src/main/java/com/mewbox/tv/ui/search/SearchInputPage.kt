package com.mewbox.tv.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.ui.components.glassTopBarSurface
import com.mewbox.tv.ui.theme.MewBoxTheme
import kotlinx.coroutines.delay

/**
 * 搜索输入界面（对齐 MewPlayer fragment_search.xml 风格，支持设备默认展示 AVBox 液态玻璃效果）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchInputPage(
    state: SearchUiState,
    onBack: () -> Unit,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onWordClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteHistory: (String) -> Unit = {},
    onOpenSiteFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // 进入搜索页自动聚焦输入框并弹出软键盘（等待转场稳定及窗口获取焦点，对齐 FongMi 250ms 体验）
    LaunchedEffect(Unit) {
        runCatching {
            focusRequester.requestFocus()
        }
        delay(200)
        runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val barFallback = MewBoxTheme.colors.searchBarFallback
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    // 顶部搜索 Toolbar
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回箭头
        Box(
            modifier = Modifier
                .size(40.dp)
                .glassTopBarSurface(CircleShape, barFallback)
                .clickable(onClick = {
                    keyboard?.hide()
                    onBack()
                }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))

        // 搜索输入胶囊
        Row(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .glassTopBarSurface(
                    shape = ContinuousCapsule,
                    fallbackColor = barFallback,
                    pressEffect = false,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (state.keyword.isEmpty()) {
                    Text(
                        text = "搜索影片、演员、剧集...",
                        color = onSurfaceVariant,
                        fontSize = 14.5.sp,
                    )
                }
                BasicTextField(
                    value = state.keyword,
                    onValueChange = onKeywordChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = onSurface,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(primaryColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            onSearch()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            // 清除文本按钮
            if (state.keyword.isNotEmpty()) {
                IconButton(
                    onClick = { onKeywordChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清除",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 指定站点按钮
        Box(
            modifier = Modifier
                .size(38.dp)
                .glassTopBarSurface(CircleShape, barFallback)
                .clickable(onClick = onOpenSiteFilter),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "指定搜索站点",
                tint = if (state.searchTargetSiteKey != null) primaryColor else onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }

    // 主体内容：搜索历史 + 3大热搜榜单
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 搜索历史（对齐 MewPlayer adapter_search_record.xml: 28dp 圆角胶囊）
        if (state.history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "搜索历史",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                IconButton(onClick = onClearHistory, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "清空历史",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val isDark = MewBoxTheme.isDark
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.history.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = if (isDark) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .combinedClickable(
                                onClick = {
                                    keyboard?.hide()
                                    onWordClick(item)
                                },
                                onLongClick = {
                                    onDeleteHistory(item)
                                },
                            ),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = item,
                                fontSize = 13.5.sp,
                                color = onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // 3大热播榜单（严格对齐 MewPlayer fragment_search.xml & adapter_search_hot_word.xml 2列排行）
        HotRankCategorySection(
            title = "电视剧热播榜",
            words = state.hotTvList,
            onWordClick = {
                keyboard?.hide()
                onWordClick(it)
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        HotRankCategorySection(
            title = "电影热播榜",
            words = state.hotMovieList,
            onWordClick = {
                keyboard?.hide()
                onWordClick(it)
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        HotRankCategorySection(
            title = "综艺热播榜",
            words = state.hotVarietyList,
            onWordClick = {
                keyboard?.hide()
                onWordClick(it)
            },
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

/**
 * 热搜榜单分类区域：标题 + 2 列排行网格（对齐 MewPlayer adapter_search_hot_word.xml）
 */
@Composable
private fun HotRankCategorySection(
    title: String,
    words: List<String>,
    onWordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // 2 列排行：每行展示 2 个条目（左列 1, 3, 5... 右列 2, 4, 6... 或顺序流排）
        val rowCount = (words.size + 1) / 2
        for (rowIndex in 0 until rowCount) {
            val leftIndex = rowIndex * 2
            val rightIndex = leftIndex + 1

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧条目
                if (leftIndex < words.size) {
                    HotRankItem(
                        rank = leftIndex + 1,
                        text = words[leftIndex],
                        onClick = { onWordClick(words[leftIndex]) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧条目
                if (rightIndex < words.size) {
                    HotRankItem(
                        rank = rightIndex + 1,
                        text = words[rightIndex],
                        onClick = { onWordClick(words[rightIndex]) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 榜单单条目（对齐 MewPlayer adapter_search_hot_word: 序号 + 片名）
 */
@Composable
private fun HotRankItem(
    rank: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号：前三名高亮橙色，其余柔和灰，粗体斜体
        Text(
            text = rank.toString(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            color = if (rank <= 3) Color(0xFFFA8C16) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )

        Spacer(modifier = Modifier.width(6.dp))

        // 片名
        Text(
            text = text,
            fontSize = 14.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
